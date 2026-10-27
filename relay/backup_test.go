package main

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
)

func testSession(t *testing.T, home string) *session {
	t.Helper()
	agent := filepath.Join(home, ".pig", "agent")
	ui := filepath.Join(home, ".pig", "android-ui")
	return newSession("token", home, ui, filepath.Join(agent, "sessions"), "/bin/true")
}

func writeFile(t *testing.T, path, body string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
}

func TestBackupRoundTrip(t *testing.T) {
	home := t.TempDir()
	writeFile(t, filepath.Join(home, ".pig", "agent", "models.json"), `{"providers":{}}`)
	writeFile(t, filepath.Join(home, ".pig", "agent", "auth.json"), `{"key":"sk-test"}`)
	writeFile(t, filepath.Join(home, ".pig", "agent", "sessions", "proj", "s1.jsonl"), `{"id":"s1"}`)
	writeFile(t, filepath.Join(home, ".pig", "state", "pig-standard", "login.json"), `{"user":"u"}`)
	writeFile(t, filepath.Join(home, ".pig", "android-ui", "theme", "base.json"), `{"name":"base"}`)
	writeFile(t, filepath.Join(home, ".pig", "agent", "crash-output", "boom.txt"), "skip me")
	writeFile(t, filepath.Join(home, ".pig", "agent", "exit.log"), "skip me too")

	s := testSession(t, home)
	var buf bytes.Buffer
	entries, err := s.newBackupFilter(true, true).write(&buf)
	if err != nil {
		t.Fatalf("write: %v", err)
	}
	if entries != 5 {
		t.Fatalf("entries = %d, want 5", entries)
	}

	var names []string
	gz, err := gzip.NewReader(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err != nil {
			break
		}
		names = append(names, hdr.Name)
	}
	joined := strings.Join(names, "\n")
	if strings.Contains(joined, "crash-output") || strings.Contains(joined, "exit.log") {
		t.Fatalf("excluded files present: %s", joined)
	}
	if !strings.Contains(joined, ".pig/agent/auth.json") {
		t.Fatalf("auth.json missing (secrets must be backed up): %s", joined)
	}

	// Restore into a fresh home and compare.
	home2 := t.TempDir()
	if err := os.WriteFile(filepath.Join(home2, "archive.tar.gz"), buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := extractArchive(filepath.Join(home2, "archive.tar.gz"), home2); err != nil {
		t.Fatalf("extract: %v", err)
	}
	for _, rel := range []string{
		".pig/agent/models.json",
		".pig/agent/auth.json",
		".pig/agent/sessions/proj/s1.jsonl",
		".pig/state/pig-standard/login.json",
		".pig/android-ui/theme/base.json",
	} {
		got, err := os.ReadFile(filepath.Join(home2, rel))
		if err != nil {
			t.Fatalf("%s: %v", rel, err)
		}
		if len(got) == 0 {
			t.Fatalf("%s is empty", rel)
		}
	}
}

func TestBackupWithoutSessions(t *testing.T) {
	home := t.TempDir()
	writeFile(t, filepath.Join(home, ".pig", "agent", "settings.json"), `{}`)
	writeFile(t, filepath.Join(home, ".pig", "agent", "sessions", "proj", "s1.jsonl"), `{"id":"s1"}`)
	s := testSession(t, home)
	var buf bytes.Buffer
	if _, err := s.newBackupFilter(false, false).write(&buf); err != nil {
		t.Fatal(err)
	}
	gz, err := gzip.NewReader(bytes.NewReader(buf.Bytes()))
	if err != nil {
		t.Fatal(err)
	}
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err != nil {
			break
		}
		if strings.HasPrefix(hdr.Name, ".pig/agent/sessions/") || strings.HasPrefix(hdr.Name, ".pig/android-ui/") {
			t.Fatalf("unexpected entry %q", hdr.Name)
		}
	}
}

func TestExtractRejectsTraversal(t *testing.T) {
	home := t.TempDir()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	add := func(name, body string) {
		hdr := &tar.Header{Name: name, Mode: 0o600, Size: int64(len(body)), Typeflag: tar.TypeReg}
		if err := tw.WriteHeader(hdr); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write([]byte(body)); err != nil {
			t.Fatal(err)
		}
	}
	add("../escape.txt", "no")
	add(".pig/../../escape2.txt", "no")
	add("/tmp/escape3.txt", "no")
	add("other/escape4.txt", "no")
	add(".pig/agent/ok.json", "yes")
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	archive := filepath.Join(home, "a.tar.gz")
	if err := os.WriteFile(archive, buf.Bytes(), 0o600); err != nil {
		t.Fatal(err)
	}
	entries, err := extractArchive(archive, home)
	if err != nil {
		t.Fatalf("extract: %v", err)
	}
	if entries != 1 {
		t.Fatalf("entries = %d, want 1", entries)
	}
	for _, p := range []string{
		filepath.Join(home, "..", "escape.txt"),
		filepath.Join(home, "..", "escape2.txt"),
		filepath.Join(home, "other", "escape4.txt"),
	} {
		if _, err := os.Stat(p); err == nil {
			t.Fatalf("traversal entry written: %s", p)
		}
	}
	if _, err := os.Stat("/tmp/escape3.txt"); err == nil {
		t.Fatal("absolute entry written")
	}
	if _, err := os.Stat(filepath.Join(home, ".pig", "agent", "ok.json")); err != nil {
		t.Fatalf("legitimate entry missing: %v", err)
	}
}

func TestChunkWriterSplitsAndChecksums(t *testing.T) {
	var frames []frame
	var cancel atomic.Bool
	w := &chunkWriter{
		send: func(v any) error {
			frames = append(frames, v.(frame))
			return nil
		},
		id:     "b1",
		cancel: &cancel,
		sha:    sha256.New(),
	}
	payload := bytes.Repeat([]byte("abcdef"), (backupChunkSize/6)*2+10)
	if _, err := w.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if len(frames) != 3 {
		t.Fatalf("chunks = %d, want 3", len(frames))
	}
	var joined []byte
	for _, f := range frames {
		chunk, err := base64.StdEncoding.DecodeString(f["data"].(string))
		if err != nil {
			t.Fatal(err)
		}
		joined = append(joined, chunk...)
	}
	if !bytes.Equal(joined, payload) {
		t.Fatal("chunk payload mismatch")
	}
	sum := sha256.Sum256(payload)
	if w.checksum() != hex.EncodeToString(sum[:]) {
		t.Fatal("checksum mismatch")
	}

	cancel.Store(true)
	if _, err := w.Write([]byte("x")); err == nil {
		t.Fatal("expected cancellation error")
	}
}
