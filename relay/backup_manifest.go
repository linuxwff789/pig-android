package main

import (
	"archive/tar"
	"compress/gzip"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"
)

// Incremental backups compare the files in ~/.pig with the manifest of the
// previous archive and package only what changed. The manifest lives in
// ~/.pig/backup/state.json, outside the backup roots, so it is never backed up
// into itself.
//
// Every archive starts with a `.pig/.backup-meta.json` entry that names its
// kind, the archive it was based on and the files that disappeared. The
// extractor reads that entry and removes the deleted files instead of writing
// it to disk, so a restore does not need the WebDAV index to be correct.
const (
	backupMetaEntry = ".pig/.backup-meta.json"
	// A metadata entry is a small JSON document; refusing anything larger
	// keeps a crafted archive from allocating a gigabyte before validation.
	backupMetaMaxBytes    = 1 << 20
	backupKindFull        = "full"
	backupKindInc         = "inc"
	backupManifestVersion = 1
)

type backupFileMeta struct {
	Size   int64  `json:"size"`
	Mtime  int64  `json:"mtime"`
	Sha256 string `json:"sha256"`
}

type backupManifest struct {
	Version int                       `json:"version"`
	Files   map[string]backupFileMeta `json:"files"`
}

// backupArchiveMeta is the metadata header written first in each archive.
type backupArchiveMeta struct {
	Version int      `json:"version"`
	Kind    string   `json:"kind"`
	Base    string   `json:"base,omitempty"`
	Parent  string   `json:"parent,omitempty"`
	Created int64    `json:"created"`
	Deleted []string `json:"deleted,omitempty"`
}

type backupState struct {
	LastPackage string          `json:"lastPackage"`
	Manifest    *backupManifest `json:"manifest,omitempty"`
}

// One backup at a time is already enforced by transfer.busy; this mutex only
// covers the state file itself.
var backupStateMu sync.Mutex

func backupStatePath(home string) string {
	return filepath.Join(home, ".pig", "backup", "state.json")
}

func loadBackupState(home string) *backupState {
	data, err := os.ReadFile(backupStatePath(home))
	if err != nil {
		return nil
	}
	var state backupState
	if err := json.Unmarshal(data, &state); err != nil || state.Manifest == nil || state.Manifest.Files == nil {
		return nil
	}
	return &state
}

func saveBackupState(home string, state *backupState) error {
	backupStateMu.Lock()
	defer backupStateMu.Unlock()
	path := backupStatePath(home)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return err
	}
	data, err := json.Marshal(state)
	if err != nil {
		return err
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// hashBackupFile hashes what it actually read; a session that grew while the
// backup was running then records the size that matches the digest.
func hashBackupFile(path string) (backupFileMeta, error) {
	file, err := os.Open(path)
	if err != nil {
		return backupFileMeta{}, err
	}
	defer file.Close()
	h := sha256.New()
	size, err := io.Copy(h, file)
	if err != nil {
		return backupFileMeta{}, err
	}
	info, err := file.Stat()
	if err != nil {
		return backupFileMeta{}, err
	}
	return backupFileMeta{
		Size:   size,
		Mtime:  info.ModTime().UnixNano(),
		Sha256: hex.EncodeToString(h.Sum(nil)),
	}, nil
}

// buildManifest walks the filter and returns the current manifest, the set of
// files whose content changed since prev, and the paths prev had but the disk
// no longer does.
//
// Every file is hashed on every run. A size+mtime shortcut is not safe here:
// Android file systems can keep the same mtime for two writes of equal length
// in the same tick, so a settings.json edit would be skipped. Reading ~150 MB
// takes about a second on device, which is cheaper than a silently incomplete
// backup.
func (f backupFilter) buildManifest(prev *backupManifest) (*backupManifest, map[string]bool, []string) {
	manifest := &backupManifest{
		Version: backupManifestVersion,
		Files:   make(map[string]backupFileMeta),
	}
	changed := make(map[string]bool)
	_, _, _ = f.walk(func(path string, info os.FileInfo) error {
		rel, err := filepath.Rel(f.home, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if rel == "." || rel == ".." || strings.HasPrefix(rel, "../") {
			return nil
		}
		meta, err := hashBackupFile(path)
		if err != nil {
			// The file vanished between walk and read (a session being rotated),
			// or it is temporarily unreadable. Keep the previous digest when
			// there is one: dropping it from the manifest would put the path in
			// `deleted` and the next restore would remove a file that still
			// exists.
			if prev != nil {
				if old, ok := prev.Files[rel]; ok {
					manifest.Files[rel] = old
				}
			}
			return nil
		}
		manifest.Files[rel] = meta
		if prev == nil {
			changed[rel] = true
			return nil
		}
		if old, ok := prev.Files[rel]; !ok || old.Sha256 != meta.Sha256 {
			changed[rel] = true
		}
		return nil
	})
	var deleted []string
	if prev != nil {
		for rel := range prev.Files {
			if _, ok := manifest.Files[rel]; !ok {
				deleted = append(deleted, rel)
			}
		}
		sort.Strings(deleted)
	}
	return manifest, changed, deleted
}

// writePackage writes an archive with the metadata entry first, then either
// every file (include == nil) or only the named ones. The returned set names
// the files that actually made it into the archive; the caller compares it
// with the manifest so a file that vanished between the two walks is not
// recorded as archived.
func (f backupFilter) writePackage(w io.Writer, meta backupArchiveMeta, include map[string]bool) (written int, archived map[string]bool, err error) {
	gz, err := gzip.NewWriterLevel(w, gzip.BestSpeed)
	if err != nil {
		return 0, nil, err
	}
	tw := tar.NewWriter(gz)
	metaBytes, err := json.Marshal(meta)
	if err != nil {
		return 0, nil, err
	}
	if err := writeTarEntry(tw, backupMetaEntry, metaBytes, time.Unix(meta.Created, 0)); err != nil {
		_ = tw.Close()
		_ = gz.Close()
		return 0, nil, err
	}
	archived = make(map[string]bool)
	_, _, err = f.walk(func(path string, info os.FileInfo) error {
		rel, err := filepath.Rel(f.home, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if rel == "." || rel == ".." || strings.HasPrefix(rel, "../") {
			return nil
		}
		if include != nil && !include[rel] {
			return nil
		}
		copied, err := writeTarFile(tw, rel, path, info)
		if err != nil {
			return err
		}
		if !copied {
			return nil
		}
		archived[rel] = true
		written++
		return nil
	})
	if err != nil {
		_ = tw.Close()
		_ = gz.Close()
		return written, archived, err
	}
	if err := tw.Close(); err != nil {
		_ = gz.Close()
		return written, archived, err
	}
	return written, archived, gz.Close()
}

func writeTarEntry(tw *tar.Writer, name string, body []byte, mod time.Time) error {
	hdr := &tar.Header{
		Name:     name,
		Mode:     0o600,
		Size:     int64(len(body)),
		ModTime:  mod,
		Typeflag: tar.TypeReg,
		Format:   tar.FormatPAX,
	}
	if err := tw.WriteHeader(hdr); err != nil {
		return err
	}
	_, err := tw.Write(body)
	return err
}

func writeTarFile(tw *tar.Writer, rel, path string, info os.FileInfo) (bool, error) {
	// Open before writing the header: a file that vanished between the walk
	// and this point must not leave a zero-filled member in the archive that
	// a restore would then write over the live file.
	src, err := os.Open(path)
	if err != nil {
		return false, nil
	}
	defer src.Close()
	hdr := &tar.Header{
		Name:     rel,
		Mode:     0o600,
		Size:     info.Size(),
		ModTime:  info.ModTime(),
		Typeflag: tar.TypeReg,
		Format:   tar.FormatPAX,
	}
	if err := tw.WriteHeader(hdr); err != nil {
		return false, err
	}
	copied, copyErr := io.Copy(tw, io.LimitReader(src, info.Size()))
	if copyErr != nil {
		return false, copyErr
	}
	// A session file can shrink between walk and copy (pig rewrites it).
	// The tar header already announced the old size; padding keeps the
	// archive valid instead of failing the whole backup at Close().
	if copied < info.Size() {
		if _, err := io.CopyN(tw, zeroReader{}, info.Size()-copied); err != nil {
			return false, err
		}
	}
	return true, nil
}
