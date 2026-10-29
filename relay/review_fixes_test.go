package main

import (
	"archive/tar"
	"compress/gzip"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
)

func TestValidateSessionPath(t *testing.T) {
	dir := t.TempDir()
	good := filepath.Join(dir, "session-1.jsonl")
	if err := os.WriteFile(good, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := validateSessionPath(good, dir); err != nil {
		t.Fatalf("valid session rejected: %v", err)
	}
	for _, bad := range []string{
		filepath.Join(dir, "..", "escape.jsonl"),
		filepath.Join(dir, "notes.txt"),
		"/etc/passwd",
		"",
	} {
		if err := validateSessionPath(bad, dir); err == nil {
			t.Errorf("expected rejection for %q", bad)
		}
	}
}

func writeTgz(t *testing.T, path string, files map[string][]byte) {
	t.Helper()
	f, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	gz := gzip.NewWriter(f)
	tw := tar.NewWriter(gz)
	for name, body := range files {
		if err := tw.WriteHeader(&tar.Header{
			Name:     name,
			Mode:     0o600,
			Size:     int64(len(body)),
			Typeflag: tar.TypeReg,
			Format:   tar.FormatPAX,
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write(body); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	if err := f.Close(); err != nil {
		t.Fatal(err)
	}
}

func TestExtractArchiveBudgets(t *testing.T) {
	oldBytes, oldEntries := backupMaxExtracted, backupMaxEntries
	defer func() { backupMaxExtracted, backupMaxEntries = oldBytes, oldEntries }()

	archive := filepath.Join(t.TempDir(), "a.tar.gz")
	writeTgz(t, archive, map[string][]byte{
		".pig/a": []byte("0123456789"),
		".pig/b": []byte("0123456789"),
	})

	home := t.TempDir()
	backupMaxExtracted, backupMaxEntries = 15, 200000
	if _, err := extractArchive(archive, home); err == nil {
		t.Fatal("expected the byte budget to reject the archive")
	}
	backupMaxExtracted, backupMaxEntries = 2<<30, 1
	if _, err := extractArchive(archive, home); err == nil {
		t.Fatal("expected the entry budget to reject the archive")
	}
	backupMaxExtracted, backupMaxEntries = 2<<30, 200000
	if _, err := extractArchive(archive, home); err != nil {
		t.Fatalf("archive within budget rejected: %v", err)
	}
}

func TestExtractArchiveCancel(t *testing.T) {
	archive := filepath.Join(t.TempDir(), "a.tar.gz")
	writeTgz(t, archive, map[string][]byte{".pig/a": []byte("x")})
	_, err := extractArchiveCancellable(archive, t.TempDir(), func() bool { return true })
	if err != errTransferCancelled {
		t.Fatalf("want errTransferCancelled, got %v", err)
	}
}

func TestDetachKeepsNewConnTransfer(t *testing.T) {
	s := &session{agents: map[string]*agentProc{}}
	newConn, peer := net.Pipe()
	defer peer.Close()
	defer newConn.Close()
	s.conn = newConn
	s.connGen = 2
	s.transfer.busy = true
	s.transfer.connGen = 2

	// The previous connection's detach runs after attach() bumped connGen.
	oldConn, oldPeer := net.Pipe()
	defer oldPeer.Close()
	s.detach(oldConn, 1)

	if !s.transfer.busy {
		t.Fatal("detach of an old connection cleared the new client's transfer")
	}
	if s.conn != newConn {
		t.Fatal("detach of an old connection cleared the new socket")
	}
}

func TestWriteSettingsFileConcurrent(t *testing.T) {
	path := filepath.Join(t.TempDir(), "settings.json")
	var wg sync.WaitGroup
	for i := 0; i < 24; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			root := map[string]interface{}{"value": fmt.Sprint(n), "nested": map[string]interface{}{"n": n}}
			if err := writeSettingsFile(path, root); err != nil {
				t.Errorf("write %d: %v", n, err)
			}
		}(i)
	}
	wg.Wait()
	if _, err := readSettingsFile(path); err != nil {
		t.Fatalf("settings.json unreadable after concurrent writes: %v", err)
	}
	matches, _ := filepath.Glob(filepath.Join(filepath.Dir(path), ".settings-*.tmp"))
	if len(matches) != 0 {
		t.Fatalf("temp files left behind: %v", matches)
	}
}

func TestExtractRefusesSymlinkedDirectory(t *testing.T) {
	home := t.TempDir()
	outside := t.TempDir()
	if err := os.MkdirAll(filepath.Join(home, ".pig"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outside, filepath.Join(home, ".pig", "agent")); err != nil {
		t.Skipf("symlink not supported: %v", err)
	}
	archive := filepath.Join(t.TempDir(), "a.tar.gz")
	writeTgz(t, archive, map[string][]byte{".pig/agent/models.json": []byte("{}")})
	if _, err := extractArchive(archive, home); err == nil {
		t.Fatal("expected extraction through a symlinked directory to fail")
	}
	if _, err := os.Stat(filepath.Join(outside, "models.json")); !os.IsNotExist(err) {
		t.Fatalf("file escaped the home tree: %v", err)
	}
}

func TestPruneTreeRemovesStaleFilesAndDirectories(t *testing.T) {
	dir := t.TempDir()
	if err := os.MkdirAll(filepath.Join(dir, "old", "nested"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "old", "nested", "x.go"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "stale.go"), []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "keep.go"), []byte("package p"), 0o644); err != nil {
		t.Fatal(err)
	}
	files := map[string][]byte{"keep.go": []byte("package p")}
	pruneTree(dir, files, ".embedded-hash")
	if _, err := os.Stat(filepath.Join(dir, "old")); !os.IsNotExist(err) {
		t.Fatal("stale subdirectory survived")
	}
	if _, err := os.Stat(filepath.Join(dir, "stale.go")); !os.IsNotExist(err) {
		t.Fatal("stale file survived")
	}
	if _, err := os.Stat(filepath.Join(dir, "keep.go")); err != nil {
		t.Fatal("wanted file was removed")
	}
	if !treeMatches(dir, files, ".embedded-hash") {
		t.Fatal("tree should match after pruning")
	}
}
