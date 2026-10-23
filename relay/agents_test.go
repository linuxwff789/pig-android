package main

import (
	"os"
	"path/filepath"
	"testing"
)

func writeSession(t *testing.T, path string, entries []string) {
	writeSessionFile(t, path, entries)
}

func writeSessionFile(t *testing.T, path string, entries []string) {
	if t != nil {
		t.Helper()
	}
	f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		if t != nil {
			t.Fatal(err)
		}
		panic(err)
	}
	defer f.Close()
	for _, entry := range entries {
		if _, err := f.WriteString(entry + "\n"); err != nil {
			if t != nil {
				t.Fatal(err)
			}
			panic(err)
		}
	}
}

func TestScanMessagesSkipsMetadata(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.jsonl")
	writeSession(t, path, []string{
		`{"type":"session","id":"s1"}`,
		`{"type":"model_change","provider":"eu"}`,
		`{"type":"message","message":{"role":"user","content":"hi"}}`,
		`{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"there"}]}}`,
	})
	messages := readSessionMessages(path)
	if len(messages) != 2 {
		t.Fatalf("want 2 messages, got %d", len(messages))
	}
}

func TestCachedMessagesReadsOnlyTheTail(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.jsonl")
	s := &session{msgCache: map[string]msgCacheEntry{}}
	head := []string{
		`{"type":"session","id":"s1"}`,
		`{"type":"message","message":{"role":"user","content":"one"}}`,
		`{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"two"}]}}`,
	}
	writeSession(t, path, head)
	first := s.cachedMessages(path)
	if len(first) != 2 {
		t.Fatalf("first read: want 2, got %d", len(first))
	}

	// Append a whole turn, then ask again: the cache must return the same
	// result as a full scan of the final file.
	writeSession(t, path, []string{
		`{"type":"message","message":{"role":"user","content":"three"}}`,
		`{"type":"message","message":{"role":"assistant","content":[{"type":"text","text":"four"}]}}`,
	})
	merged := s.cachedMessages(path)
	full := readSessionMessages(path)
	if len(merged) != len(full) || len(merged) != 4 {
		t.Fatalf("incremental=%d full=%d (want 4)", len(merged), len(full))
	}
	for i := range full {
		if string(merged[i]) != string(full[i]) {
			t.Fatalf("message %d differs: %s vs %s", i, merged[i], full[i])
		}
	}
}

func TestCachedMessagesRebuildsWhenFileShrinks(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "session.jsonl")
	s := &session{msgCache: map[string]msgCacheEntry{}}
	writeSession(t, path, []string{
		`{"type":"message","message":{"role":"user","content":"a"}}`,
		`{"type":"message","message":{"role":"user","content":"b"}}`,
	})
	if got := len(s.cachedMessages(path)); got != 2 {
		t.Fatalf("want 2, got %d", got)
	}
	// A rewrite to a shorter file must not be served from the stale cache.
	if err := os.WriteFile(path, []byte(`{"type":"message","message":{"role":"user","content":"c"}}`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := len(s.cachedMessages(path)); got != 1 {
		t.Fatalf("after shrink: want 1, got %d", got)
	}
}

// BenchmarkHistoryLoad measures the real-world case: a multi-MB session that
// grows by one entry. Set PIG_BENCH_SESSION to a session JSONL path.
func BenchmarkHistoryLoad(b *testing.B) {
	source := os.Getenv("PIG_BENCH_SESSION")
	if source == "" {
		b.Skip("set PIG_BENCH_SESSION to a session file")
	}
	data, err := os.ReadFile(source)
	if err != nil {
		b.Skip(err)
	}
	dir := b.TempDir()
	path := filepath.Join(dir, "session.jsonl")
	if err := os.WriteFile(path, data, 0o600); err != nil {
		b.Fatal(err)
	}
	s := &session{msgCache: map[string]msgCacheEntry{}}
	b.Run("full", func(b *testing.B) {
		for i := 0; i < b.N; i++ {
			readSessionMessages(path)
		}
	})
	b.Run("incremental", func(b *testing.B) {
		for i := 0; i < b.N; i++ {
			s.cachedMessages(path) // warm cache, then one new line
			writeSessionFile(nil, path, []string{`{"type":"message","message":{"role":"user","content":"x"}}`})
		}
	})
}
