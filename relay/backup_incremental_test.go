package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync/atomic"
	"testing"
)

// archiveNames lists the entries of a package, in order.
func archiveNames(t *testing.T, data []byte) []string {
	t.Helper()
	gz, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("gzip: %v", err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	var names []string
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatalf("tar: %v", err)
		}
		names = append(names, hdr.Name)
	}
	return names
}

func archiveBody(t *testing.T, data []byte, name string) string {
	t.Helper()
	gz, err := gzip.NewReader(bytes.NewReader(data))
	if err != nil {
		t.Fatalf("gzip: %v", err)
	}
	defer gz.Close()
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err == io.EOF {
			return ""
		}
		if err != nil {
			t.Fatalf("tar: %v", err)
		}
		if hdr.Name != name {
			continue
		}
		var buf bytes.Buffer
		if _, err := io.Copy(&buf, tr); err != nil {
			t.Fatalf("read %s: %v", name, err)
		}
		return buf.String()
	}
}

func writeArchiveFile(t *testing.T, dir, name string, data []byte) string {
	t.Helper()
	path := filepath.Join(dir, name)
	if err := os.WriteFile(path, data, 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

// An increment contains the metadata entry plus only the files whose content
// changed, and records the files that disappeared.
func TestIncrementalPackageOnlyChanges(t *testing.T) {
	home := t.TempDir()
	agent := filepath.Join(home, ".pig", "agent")
	s := testSession(t, home)
	writeFile(t, filepath.Join(agent, "a.json"), "A1")
	writeFile(t, filepath.Join(agent, "b.json"), "B1")
	writeFile(t, filepath.Join(agent, "c.json"), "C1")
	writeFile(t, filepath.Join(agent, "sub", "keep.txt"), "K1")

	filter := s.newBackupFilter(true, true)
	first, _, _ := filter.buildManifest(nil)
	if len(first.Files) != 4 {
		t.Fatalf("manifest files = %d, want 4", len(first.Files))
	}

	var full bytes.Buffer
	if _, _, err := filter.writePackage(&full, backupArchiveMeta{Version: 1, Kind: backupKindFull, Created: 1}, nil); err != nil {
		t.Fatalf("full package: %v", err)
	}
	fullNames := archiveNames(t, full.Bytes())
	if len(fullNames) != 5 || fullNames[0] != backupMetaEntry {
		t.Fatalf("full entries = %v", fullNames)
	}

	// One file changes, one is removed, one appears, one is untouched.
	writeFile(t, filepath.Join(agent, "b.json"), "B2")
	if err := os.Remove(filepath.Join(agent, "c.json")); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(agent, "d.json"), "D1")

	second, changed, deleted := filter.buildManifest(first)
	if len(changed) != 2 || !changed[".pig/agent/b.json"] || !changed[".pig/agent/d.json"] {
		t.Fatalf("changed = %v", changed)
	}
	if len(deleted) != 1 || deleted[0] != ".pig/agent/c.json" {
		t.Fatalf("deleted = %v", deleted)
	}

	var inc bytes.Buffer
	if _, _, err := filter.writePackage(&inc, backupArchiveMeta{
		Version: 1, Kind: backupKindInc, Base: "pig-full-x.tar.gz", Created: 2, Deleted: deleted,
	}, changed); err != nil {
		t.Fatalf("inc package: %v", err)
	}
	names := archiveNames(t, inc.Bytes())
	sort.Strings(names)
	want := []string{backupMetaEntry, ".pig/agent/b.json", ".pig/agent/d.json"}
	if strings.Join(names, ",") != strings.Join(want, ",") {
		t.Fatalf("inc entries = %v, want %v", names, want)
	}
	if body := archiveBody(t, inc.Bytes(), ".pig/agent/b.json"); body != "B2" {
		t.Fatalf("b.json = %q", body)
	}
	if second.Files[".pig/agent/a.json"].Sha256 == "" {
		t.Fatal("untouched file lost its digest")
	}
}

// Extracting a full archive and then an increment yields the live tree at the
// time of the increment, including the files the increment deleted.
func TestIncrementalRestoreAppliesDeletions(t *testing.T) {
	home := t.TempDir()
	agent := filepath.Join(home, ".pig", "agent")
	s := testSession(t, home)
	writeFile(t, filepath.Join(agent, "a.json"), "A1")
	writeFile(t, filepath.Join(agent, "b.json"), "B1")
	writeFile(t, filepath.Join(agent, "gone.json"), "G1")

	filter := s.newBackupFilter(true, true)
	first, _, _ := filter.buildManifest(nil)
	var full bytes.Buffer
	if _, _, err := filter.writePackage(&full, backupArchiveMeta{Kind: backupKindFull, Created: 1}, nil); err != nil {
		t.Fatal(err)
	}

	writeFile(t, filepath.Join(agent, "b.json"), "B2")
	if err := os.Remove(filepath.Join(agent, "gone.json")); err != nil {
		t.Fatal(err)
	}
	_, changed, deleted := filter.buildManifest(first)
	var inc bytes.Buffer
	if _, _, err := filter.writePackage(&inc, backupArchiveMeta{
		Kind: backupKindInc, Base: "pig-full-x", Created: 2, Deleted: deleted,
	}, changed); err != nil {
		t.Fatal(err)
	}

	target := t.TempDir()
	dir := t.TempDir()
	fullPath := writeArchiveFile(t, dir, "full.tar.gz", full.Bytes())
	incPath := writeArchiveFile(t, dir, "inc.tar.gz", inc.Bytes())
	if _, err := extractArchive(fullPath, target); err != nil {
		t.Fatalf("extract full: %v", err)
	}
	if _, err := extractArchive(incPath, target); err != nil {
		t.Fatalf("extract inc: %v", err)
	}

	read := func(rel string) string {
		data, err := os.ReadFile(filepath.Join(target, rel))
		if err != nil {
			t.Fatalf("read %s: %v", rel, err)
		}
		return string(data)
	}
	if got := read(".pig/agent/b.json"); got != "B2" {
		t.Fatalf("b.json = %q, want B2", got)
	}
	if got := read(".pig/agent/a.json"); got != "A1" {
		t.Fatalf("a.json = %q, want A1", got)
	}
	if _, err := os.Stat(filepath.Join(target, ".pig", "agent", "gone.json")); !os.IsNotExist(err) {
		t.Fatalf("gone.json still exists: %v", err)
	}
	if _, err := os.Stat(filepath.Join(target, backupMetaEntry)); !os.IsNotExist(err) {
		t.Fatalf("metadata entry was written to disk")
	}
}

// The relay only produces an increment for the package the app says it has;
// an unknown base falls back to a full archive instead of a broken chain.
func TestBackupStateDecidesIncremental(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	m := &memConn{}
	s.conn, s.out, s.connGen = m, &connWriter{conn: m}, 1
	writeFile(t, filepath.Join(home, ".pig", "agent", "one.json"), "1")

	cancel := &atomic.Bool{}
	s.streamBackup("b1", backupOptions{IncludeSessions: true, IncludeUI: true, Incremental: true}, cancel)
	frames := m.frames(t)
	start := frames[0]
	if start["type"] != "relay_backup_start" || start["kind"] != backupKindFull {
		t.Fatalf("first backup: %#v", frames)
	}
	name, _ := start["name"].(string)
	state := loadBackupState(home)
	if state == nil || state.LastPackage != name || state.Manifest == nil {
		t.Fatalf("state = %#v", state)
	}

	writeFile(t, filepath.Join(home, ".pig", "agent", "two.json"), "2")
	s.streamBackup("b2", backupOptions{IncludeSessions: true, IncludeUI: true, Incremental: true, Base: name}, cancel)
	frames = m.frames(t)
	if frames[0]["kind"] != backupKindInc || frames[0]["base"] != name {
		t.Fatalf("second backup: %#v", frames)
	}
	if frames[0]["entries"] != float64(1) {
		t.Fatalf("increment entries = %v, want 1", frames[0]["entries"])
	}

	s.streamBackup("b3", backupOptions{IncludeSessions: true, IncludeUI: true, Incremental: true, Base: "pig-inc-unknown.tar.gz"}, cancel)
	frames = m.frames(t)
	if frames[0]["kind"] != backupKindFull {
		t.Fatalf("unknown base should produce a full archive: %#v", frames)
	}
}

// The app marks every link after the first so the relay does not write one
// full automatic backup per link or restart the pig processes in between.
func TestRestoreFrameCarriesSkipSafetyBackup(t *testing.T) {
	s, _ := memSession(t)
	s.handleClientLine([]byte(`{"type":"relay_restore_begin","id":"r1","name":"pig-inc-x.tar.gz","size":10,"skipSafetyBackup":true}`))
	r := s.currentRestore()
	if r == nil || !r.skipSafetyBackup {
		t.Fatalf("restore session = %#v", r)
	}
	_ = r.file.Close()
	_ = os.Remove(r.path)
	s.transfer.mu.Lock()
	s.transfer.restore = nil
	s.transfer.busy = false
	s.transfer.mu.Unlock()

	s.handleClientLine([]byte(`{"type":"relay_restore_begin","id":"r2","name":"pig-full-x.tar.gz","size":10}`))
	r = s.currentRestore()
	if r == nil || r.skipSafetyBackup {
		t.Fatalf("plain restore session = %#v", r)
	}
	_ = r.file.Close()
	_ = os.Remove(r.path)
	s.transfer.mu.Lock()
	s.transfer.restore = nil
	s.transfer.busy = false
	s.transfer.mu.Unlock()
}

// A file that disappears between the manifest and the copy must not leave a
// zero-filled member behind: a restore would write zeros over the live file.
func TestWritePackageSkipsVanishedFile(t *testing.T) {
	home := t.TempDir()
	agent := filepath.Join(home, ".pig", "agent")
	s := testSession(t, home)
	writeFile(t, filepath.Join(agent, "keep.json"), "K")
	victim := filepath.Join(agent, "vanish.json")
	writeFile(t, victim, "VICTIM")

	filter := s.newBackupFilter(true, true)
	manifest, changed, _ := filter.buildManifest(nil)
	if len(manifest.Files) != 2 || len(changed) != 2 {
		t.Fatalf("manifest=%d changed=%d", len(manifest.Files), len(changed))
	}
	// Remove it after the walk that produced the manifest, before the copy.
	if err := os.Remove(victim); err != nil {
		t.Fatal(err)
	}
	var buf bytes.Buffer
	written, archived, err := filter.writePackage(&buf, backupArchiveMeta{Kind: backupKindFull}, nil)
	if err != nil {
		t.Fatalf("writePackage: %v", err)
	}
	if written != 1 || len(archived) != 1 || archived[".pig/agent/vanish.json"] {
		t.Fatalf("written=%d archived=%v", written, archived)
	}
	for _, name := range archiveNames(t, buf.Bytes()) {
		if name == ".pig/agent/vanish.json" {
			t.Fatalf("vanished file left a member in the archive")
		}
	}
}

// An unreadable file must keep its previous digest: dropping it would put the
// path in `deleted`, and the next restore would remove a file that exists.
func TestUnreadableFileIsNotDeleted(t *testing.T) {
	home := t.TempDir()
	agent := filepath.Join(home, ".pig", "agent")
	s := testSession(t, home)
	secure := filepath.Join(agent, "secret.json")
	writeFile(t, secure, "SECRET")
	writeFile(t, filepath.Join(agent, "plain.json"), "P")

	filter := s.newBackupFilter(true, true)
	first, _, _ := filter.buildManifest(nil)
	if len(first.Files) != 2 {
		t.Fatalf("manifest = %d", len(first.Files))
	}
	if err := os.Chmod(secure, 0o000); err != nil {
		t.Skipf("chmod: %v", err)
	}
	defer os.Chmod(secure, 0o600)
	if _, err := os.ReadFile(secure); err == nil {
		t.Skip("the file is still readable as this user; cannot exercise the path")
	}

	second, changed, deleted := filter.buildManifest(first)
	if len(deleted) != 0 {
		t.Fatalf("unreadable file reported as deleted: %v", deleted)
	}
	if changed[".pig/agent/secret.json"] {
		t.Fatalf("unreadable file reported as changed")
	}
	if _, ok := second.Files[".pig/agent/secret.json"]; !ok {
		t.Fatalf("unreadable file lost its previous entry")
	}
}
