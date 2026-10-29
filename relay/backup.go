// Backup and restore of the Termux-side PiG data.
//
// The app never sees the Termux file system: `~/.pig` lives in another app's
// private data dir, and RUN_COMMAND cannot hand a file back. So the relay is
// the file server. A backup is streamed as a gzipped tar cut into base64
// chunks over the same loopback socket the transcript uses; a restore is the
// same protocol in reverse, checksummed, extracted into a staging file first
// and preceded by an automatic backup of what is already there.
//
// Archive layout is relative to the Termux home: `.pig/agent/...`,
// `.pig/state/...`, `.pig/android-ui/...`. That keeps a backup restorable on a
// device whose home path differs, and the extractor refuses anything outside
// `.pig/`.
package main

import (
	"archive/tar"
	"compress/gzip"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"hash"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const (
	// Small enough that one frame stays far below the socket buffer and the
	// app can write it straight to disk; 48 KiB raw is 64 KiB of base64.
	backupChunkSize = 48 * 1024
	// A restore chunk bigger than this is a protocol error, not a busy phone.
	backupMaxChunk = 4 << 20
	// Refuse absurd archives before they fill /data/data.
	backupMaxBytes = 1 << 30
)

// relayFeatures lets the app hide a button instead of discovering a missing
// frame with "unknown relay frame".
var relayFeatures = []string{"backup", "restore", "providers", "install", "archive"}

var errTransferCancelled = errors.New("transfer cancelled")

// transferState serializes backup/restore: two at once would interleave chunk
// frames with different ids on one connection and both write to ~/.pig.
type transferState struct {
	mu      sync.Mutex
	busy    bool
	cancel  atomic.Bool
	restore *restoreSession
}

type restoreSession struct {
	file    *os.File
	path    string
	size    int64
	written int64
}

func (s *session) beginTransfer(id any, errorType string) bool {
	s.transfer.mu.Lock()
	if s.transfer.busy {
		s.transfer.mu.Unlock()
		_ = s.send(frame{
			"type":    errorType,
			"id":      id,
			"message": "another backup or restore is running",
		})
		return false
	}
	s.transfer.busy = true
	s.transfer.mu.Unlock()
	return true
}

func (s *session) finishTransfer() {
	s.transfer.mu.Lock()
	s.transfer.busy = false
	s.transfer.restore = nil
	s.transfer.mu.Unlock()
}

// backupFilter decides what goes into an archive and where it comes from.
type backupFilter struct {
	home            string
	agentDir        string
	stateDir        string
	uiDir           string
	includeSessions bool
	includeUI       bool
}

func (s *session) newBackupFilter(includeSessions, includeUI bool) backupFilter {
	agentDir := filepath.Dir(s.sessionsDir)
	return backupFilter{
		home:            s.cwd,
		agentDir:        agentDir,
		stateDir:        filepath.Join(s.cwd, ".pig", "state"),
		uiDir:           s.uiDir,
		includeSessions: includeSessions,
		includeUI:       includeUI,
	}
}

func (f backupFilter) roots() []string {
	roots := []string{f.agentDir, f.stateDir}
	if f.includeUI && f.uiDir != "" {
		roots = append(roots, f.uiDir)
	}
	return roots
}

func (f backupFilter) skipDir(path, name string) bool {
	switch name {
	case "node_modules", "__pycache__", ".git", "crash-output", "backups":
		return true
	}
	// Sessions are the bulk of the archive (tens of MB); a config-only backup
	// must not pay for them.
	if !f.includeSessions && path == filepath.Join(f.agentDir, "sessions") {
		return true
	}
	// A copy the relay wrote itself is recreated on every start. Backing it up
	// wastes space and can resurrect old extension sources over the shipped
	// ones (and a stale plan copy used to make pig refuse to start). The pigkit
	// root is exempt: user packages may live next to the managed ones, and only
	// the marked subdirectories are ours.
	if path != filepath.Join(f.agentDir, "pigkit") && f.relayManaged(path) {
		return true
	}
	return false
}

func (f backupFilter) relayManaged(path string) bool {
	_, err := os.Stat(filepath.Join(path, ".embedded-hash"))
	return err == nil
}

func (f backupFilter) skipFile(path, name string) bool {
	if strings.HasSuffix(name, ".log") || strings.HasSuffix(name, ".sock") ||
		strings.HasSuffix(name, ".pid") || name == ".DS_Store" {
		return true
	}
	// The marker and the module file are recreated with the embedded sources.
	if name == ".embedded-hash" {
		return true
	}
	pigkitRoot := filepath.Join(f.agentDir, "pigkit")
	if path == filepath.Join(pigkitRoot, "go.mod") && f.relayManaged(pigkitRoot) {
		return true
	}
	return false
}

// walk visits every file that belongs in the archive. Unreadable entries are
// skipped instead of failing the whole backup: one broken session file must
// not make the config unbackupable.
func (f backupFilter) walk(fn func(path string, info os.FileInfo) error) (int, int64, error) {
	entries := 0
	var bytes int64
	for _, root := range f.roots() {
		if root == "" {
			continue
		}
		err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
			if err != nil {
				return nil
			}
			if d.IsDir() {
				if path != root && f.skipDir(path, d.Name()) {
					return filepath.SkipDir
				}
				return nil
			}
			// Symlinks could point anywhere; the archive stores plain files.
			if d.Type()&os.ModeSymlink != 0 || !d.Type().IsRegular() {
				return nil
			}
			if f.skipFile(path, d.Name()) {
				return nil
			}
			info, err := d.Info()
			if err != nil {
				return nil
			}
			entries++
			bytes += info.Size()
			if fn != nil {
				return fn(path, info)
			}
			return nil
		})
		if err != nil {
			return entries, bytes, err
		}
	}
	return entries, bytes, nil
}

// write streams the archive into w (a gzip tar) and returns the file count.
func (f backupFilter) write(w io.Writer) (int, error) {
	gz, err := gzip.NewWriterLevel(w, gzip.BestSpeed)
	if err != nil {
		return 0, err
	}
	tw := tar.NewWriter(gz)
	written := 0
	_, _, err = f.walk(func(path string, info os.FileInfo) error {
		rel, err := filepath.Rel(f.home, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		if rel == "." || rel == ".." || strings.HasPrefix(rel, "../") {
			return nil
		}
		hdr := &tar.Header{
			Name:     rel,
			Mode:     0o600,
			Size:     info.Size(),
			ModTime:  info.ModTime(),
			Typeflag: tar.TypeReg,
			Format:   tar.FormatPAX,
		}
		if err := tw.WriteHeader(hdr); err != nil {
			return err
		}
		src, err := os.Open(path)
		if err != nil {
			// The file vanished between walk and copy (a live session being
			// rewritten): keep the archive usable and move on.
			return nil
		}
		_, copyErr := io.Copy(tw, io.LimitReader(src, info.Size()))
		_ = src.Close()
		if copyErr != nil {
			return copyErr
		}
		written++
		return nil
	})
	if err != nil {
		_ = tw.Close()
		_ = gz.Close()
		return written, err
	}
	if err := tw.Close(); err != nil {
		_ = gz.Close()
		return written, err
	}
	return written, gz.Close()
}

// chunkWriter turns the tar stream into `relay_backup_chunk` frames and hashes
// exactly the bytes that were sent, so the app can verify the received file.
type chunkWriter struct {
	send   func(any) error
	id     any
	cancel *atomic.Bool
	buf    []byte
	seq    int
	total  int64
	sha    hash.Hash
}

func newChunkWriter(s *session, id any, cancel *atomic.Bool) *chunkWriter {
	return &chunkWriter{
		send:   s.send,
		id:     id,
		cancel: cancel,
		sha:    sha256.New(),
	}
}

func (w *chunkWriter) Write(p []byte) (int, error) {
	if w.cancel != nil && w.cancel.Load() {
		return 0, errTransferCancelled
	}
	if w.total+int64(len(p)) > backupMaxBytes {
		return 0, fmt.Errorf("backup is larger than %d MiB", backupMaxBytes>>20)
	}
	w.total += int64(len(p))
	_, _ = w.sha.Write(p)
	w.buf = append(w.buf, p...)
	for len(w.buf) >= backupChunkSize {
		if err := w.flush(backupChunkSize); err != nil {
			return 0, err
		}
	}
	return len(p), nil
}

func (w *chunkWriter) Close() error {
	return w.flush(len(w.buf))
}

func (w *chunkWriter) flush(n int) error {
	if n <= 0 {
		return nil
	}
	if n > len(w.buf) {
		n = len(w.buf)
	}
	err := w.send(frame{
		"type": "relay_backup_chunk",
		"id":   w.id,
		"seq":  w.seq,
		"data": base64.StdEncoding.EncodeToString(w.buf[:n]),
	})
	if err != nil {
		return err
	}
	w.seq++
	w.buf = append(w.buf[:0], w.buf[n:]...)
	return nil
}

func (w *chunkWriter) checksum() string {
	return hex.EncodeToString(w.sha.Sum(nil))
}

// startBackup streams an archive in the background; cancel frames stop it.
func (s *session) startBackup(id any, includeSessions, includeUI bool) {
	if !s.beginTransfer(id, "relay_backup_error") {
		return
	}
	s.transfer.cancel.Store(false)
	cancel := &s.transfer.cancel
	go func() {
		defer s.finishTransfer()
		s.streamBackup(id, includeSessions, includeUI, cancel)
	}()
}

func (s *session) streamBackup(id any, includeSessions, includeUI bool, cancel *atomic.Bool) {
	filter := s.newBackupFilter(includeSessions, includeUI)
	entries, total, _ := filter.walk(nil)
	name := backupFileName("pig-backup")
	_ = s.send(frame{
		"type":    "relay_backup_start",
		"id":      id,
		"name":    name,
		"entries": entries,
		"bytes":   total,
	})
	w := newChunkWriter(s, id, cancel)
	written, err := filter.write(w)
	if err == nil {
		err = w.Close()
	}
	if err != nil {
		message := err.Error()
		cancelled := errors.Is(err, errTransferCancelled)
		if cancelled {
			message = "cancelled"
		}
		_ = s.send(frame{
			"type":      "relay_backup_error",
			"id":        id,
			"message":   message,
			"cancelled": cancelled,
		})
		return
	}
	_ = s.send(frame{
		"type":    "relay_backup_done",
		"id":      id,
		"name":    name,
		"bytes":   w.total,
		"entries": written,
		"sha256":  w.checksum(),
	})
}

func backupFileName(prefix string) string {
	return prefix + "-" + time.Now().Format("20060102-150405") + ".tar.gz"
}

// beginRestore opens the staging file. The archive is not extracted until the
// client has finished sending and the checksum matches: a truncated transfer
// must never overwrite live config.
func (s *session) beginRestore(id any, name string, size int64) {
	if !s.beginTransfer(id, "relay_restore_error") {
		return
	}
	fail := func(message string) {
		s.finishTransfer()
		_ = s.send(frame{"type": "relay_restore_error", "id": id, "message": message})
	}
	if size > backupMaxBytes {
		fail("backup is too large")
		return
	}
	dir := filepath.Join(s.cwd, ".pig", "tmp")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		fail("create staging dir: " + err.Error())
		return
	}
	file, err := os.CreateTemp(dir, "restore-*.tar.gz")
	if err != nil {
		fail("create staging file: " + err.Error())
		return
	}
	s.transfer.mu.Lock()
	s.transfer.restore = &restoreSession{
		file: file,
		path: file.Name(),
		size: size,
	}
	s.transfer.mu.Unlock()
	_ = s.send(frame{
		"type": "relay_restore_ready",
		"id":   id,
		"name": name,
		"max":  backupMaxBytes,
	})
}

// abandonTransfer runs when the client goes away: an export picks up the cancel
// flag at its next write, and a half-uploaded restore is thrown away instead of
// blocking the next transfer forever.
func (s *session) abandonTransfer() {
	s.transfer.cancel.Store(true)
	s.transfer.mu.Lock()
	r := s.transfer.restore
	if r != nil {
		s.transfer.restore = nil
		s.transfer.busy = false
	}
	s.transfer.mu.Unlock()
	if r != nil {
		_ = r.file.Close()
		_ = os.Remove(r.path)
	}
}

func (s *session) currentRestore() *restoreSession {
	s.transfer.mu.Lock()
	defer s.transfer.mu.Unlock()
	return s.transfer.restore
}

func (s *session) abortRestore(id any, message string) {
	s.transfer.mu.Lock()
	r := s.transfer.restore
	s.transfer.restore = nil
	s.transfer.mu.Unlock()
	if r != nil {
		_ = r.file.Close()
		_ = os.Remove(r.path)
	}
	_ = s.send(frame{"type": "relay_restore_error", "id": id, "message": message})
	s.finishTransfer()
}

func (s *session) restoreChunk(id any, data string) {
	r := s.currentRestore()
	if r == nil {
		_ = s.send(frame{"type": "relay_restore_error", "message": "no restore in progress"})
		return
	}
	payload, err := base64.StdEncoding.DecodeString(data)
	if err != nil {
		s.abortRestore(id, "chunk is not base64: "+err.Error())
		return
	}
	if len(payload) > backupMaxChunk {
		s.abortRestore(id, "chunk is too large")
		return
	}
	if r.size > 0 && r.written+int64(len(payload)) > r.size {
		s.abortRestore(id, "chunk exceeds the announced size")
		return
	}
	if r.written+int64(len(payload)) > backupMaxBytes {
		s.abortRestore(id, "backup is too large")
		return
	}
	if _, err := r.file.Write(payload); err != nil {
		s.abortRestore(id, "write staging file: "+err.Error())
		return
	}
	r.written += int64(len(payload))
}

func (s *session) endRestore(id any, wantSha string) {
	r := s.currentRestore()
	if r == nil {
		_ = s.send(frame{"type": "relay_restore_error", "id": id, "message": "no restore in progress"})
		return
	}
	if err := r.file.Close(); err != nil {
		s.abortRestore(id, "close staging file: "+err.Error())
		return
	}
	fail := func(message string) {
		s.transfer.mu.Lock()
		s.transfer.restore = nil
		s.transfer.mu.Unlock()
		_ = os.Remove(r.path)
		s.finishTransfer()
		_ = s.send(frame{"type": "relay_restore_error", "id": id, "message": message})
	}
	if r.size > 0 && r.written != r.size {
		fail(fmt.Sprintf("incomplete upload: %d of %d bytes", r.written, r.size))
		return
	}
	if wantSha != "" {
		sum, err := fileSHA256(r.path)
		if err != nil {
			fail("hash staging file: " + err.Error())
			return
		}
		if !strings.EqualFold(sum, wantSha) {
			fail("checksum mismatch: the transfer was corrupted")
			return
		}
	}
	s.transfer.mu.Lock()
	s.transfer.restore = nil
	s.transfer.mu.Unlock()
	go func() {
		defer s.finishTransfer()
		s.applyRestore(id, r)
	}()
}

// applyRestore makes the automatic safety backup, extracts, then stops every
// pig process: they cache settings/models/auth at startup, so the restored
// files only take effect on a fresh process (the app reconnects right after).
func (s *session) applyRestore(id any, r *restoreSession) {
	autoPath, autoErr := s.writeAutoBackup()
	if autoErr != nil {
		_ = os.Remove(r.path)
		_ = s.send(frame{
			"type":    "relay_restore_error",
			"id":      id,
			"message": "automatic backup failed, nothing was changed: " + autoErr.Error(),
		})
		return
	}
	entries, err := extractArchive(r.path, s.cwd)
	_ = os.Remove(r.path)
	if err != nil {
		_ = s.send(frame{
			"type":    "relay_restore_error",
			"id":      id,
			"message": "restore failed (automatic backup: " + autoPath + "): " + err.Error(),
		})
		return
	}
	// A backup may carry `packages` entries that point at a development
	// checkout of an extension the relay already manages (typically /plan).
	// Loading both makes pig exit with "Flag --plan conflicts"; fix the file
	// here instead of leaving the app in a reconnect/exit loop.
	removed, warning := sanitizeRestoredSettings(s.cwd)
	if len(removed) > 0 {
		logf("restore: removed conflicting package(s): %s", strings.Join(removed, ", "))
	}
	s.msgMu.Lock()
	s.msgCache = map[string]msgCacheEntry{}
	s.msgMu.Unlock()
	s.stopAllAgents()
	done := frame{
		"type":    "relay_restore_done",
		"id":      id,
		"entries": entries,
		"bytes":   r.written,
		"backup":  autoPath,
	}
	if len(removed) > 0 {
		done["settingsFixed"] = removed
	}
	if warning != "" {
		done["warning"] = warning
	}
	_ = s.send(done)
}

func (s *session) stopAllAgents() {
	s.agentMu.Lock()
	agents := make([]*agentProc, 0, len(s.agents))
	for _, a := range s.agents {
		agents = append(agents, a)
	}
	s.agents = map[string]*agentProc{}
	s.active = nil
	s.agentMu.Unlock()
	for _, a := range agents {
		a.pig.stop()
	}
}

func (s *session) writeAutoBackup() (string, error) {
	dir := filepath.Join(s.cwd, ".pig", "backups")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	path := filepath.Join(dir, backupFileName("pig-auto-before-restore"))
	file, err := os.Create(path)
	if err != nil {
		return "", err
	}
	filter := s.newBackupFilter(true, true)
	if _, err := filter.write(file); err != nil {
		_ = file.Close()
		_ = os.Remove(path)
		return "", err
	}
	if err := file.Close(); err != nil {
		_ = os.Remove(path)
		return "", err
	}
	return path, nil
}

func fileSHA256(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	h := sha256.New()
	if _, err := io.Copy(h, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// extractArchive reads a gzipped tar and writes it under home. Entries outside
// `.pig/`, absolute names and traversal (`..`) are ignored. Symlinks, devices
// and hardlinks are never created: an archive is untrusted input even when the
// token says it comes from the app.
func extractArchive(path, home string) (int, error) {
	file, err := os.Open(path)
	if err != nil {
		return 0, err
	}
	defer file.Close()
	gz, err := gzip.NewReader(file)
	if err != nil {
		return 0, fmt.Errorf("not a gzip archive: %w", err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	root := filepath.Clean(home)
	entries := 0
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return entries, err
		}
		name, ok := safeArchivePath(root, hdr.Name)
		if !ok {
			continue
		}
		switch hdr.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(name, 0o700); err != nil {
				return entries, err
			}
		case tar.TypeReg, tar.TypeRegA:
			if hdr.Size < 0 || hdr.Size > backupMaxBytes {
				continue
			}
			if err := os.MkdirAll(filepath.Dir(name), 0o700); err != nil {
				return entries, err
			}
			out, err := os.OpenFile(name, os.O_CREATE|os.O_TRUNC|os.O_WRONLY, 0o600)
			if err != nil {
				return entries, err
			}
			_, copyErr := io.CopyN(out, tr, hdr.Size)
			closeErr := out.Close()
			if copyErr != nil {
				return entries, copyErr
			}
			if closeErr != nil {
				return entries, closeErr
			}
			entries++
		default:
			// Symlinks/hardlinks/fifos have no business in a config backup.
			continue
		}
	}
	return entries, nil
}

func safeArchivePath(root, name string) (string, bool) {
	clean := filepath.Clean(filepath.FromSlash(strings.TrimPrefix(name, "./")))
	if clean == "." || clean == string(filepath.Separator) || filepath.IsAbs(clean) {
		return "", false
	}
	if clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", false
	}
	// Only PiG's own tree is restorable.
	rel := filepath.ToSlash(clean)
	if rel != ".pig" && !strings.HasPrefix(rel, ".pig/") {
		return "", false
	}
	dest := filepath.Join(root, clean)
	if dest != root && !strings.HasPrefix(dest, root+string(filepath.Separator)) {
		return "", false
	}
	return dest, true
}

// sanitizeRestoredSettings drops package entries that would load a second copy
// of an extension the relay itself installs. A backup taken on a development
// machine often has `packages` pointing at a checkout (`.../extensions/plan`);
// after a restore the embedded copy is registered too, and pig exits with
// `Flag "--plan" conflicts with ...`. Keeping the embedded copy is the better
// fix: the relay updates it with the app, while the checkout goes stale.
func sanitizeRestoredSettings(home string) (removed []string, warning string) {
	agentDir := filepath.Join(home, ".pig", "agent")
	embeddedPlan := filepath.Join(agentDir, "extensions", "plan")
	// Only touch the file when the relay's own plan extension is present;
	// without it, a single checkout entry is perfectly valid.
	if _, err := os.Stat(filepath.Join(embeddedPlan, ".embedded-hash")); err != nil {
		return nil, ""
	}
	settingsPath := filepath.Join(agentDir, "settings.json")
	root, err := readJSONObjectFile(settingsPath)
	if err != nil || len(root) == 0 {
		return nil, ""
	}
	raw, ok := root["packages"].([]any)
	if !ok {
		return nil, ""
	}
	kept := make([]any, 0, len(raw))
	hasEmbedded := false
	for _, item := range raw {
		entry, _ := item.(string)
		entry = strings.TrimSpace(entry)
		if entry == "" {
			continue
		}
		resolved := entry
		if !filepath.IsAbs(resolved) {
			resolved = filepath.Join(agentDir, resolved)
		}
		resolved = filepath.Clean(resolved)
		if filepath.Base(resolved) == "plan" {
			if resolved == embeddedPlan {
				hasEmbedded = true
			} else {
				removed = append(removed, entry)
				continue
			}
		}
		kept = append(kept, item)
	}
	if len(removed) == 0 {
		return nil, ""
	}
	if !hasEmbedded {
		kept = append(kept, "extensions/plan")
	}
	root["packages"] = kept
	if err := writeJSONAtomic(settingsPath, root); err != nil {
		return removed, "已恢复，但清理冲突的 plan 扩展失败: " + err.Error()
	}
	return removed, ""
}
