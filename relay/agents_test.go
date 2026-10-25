package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strconv"
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
		// Spaced JSON must not be skipped by the raw-line prefilter.
		`{"type": "message", "message": {"role": "user", "content": "spaced"}}`,
	})
	messages := readSessionMessages(path)
	if len(messages) != 3 {
		t.Fatalf("want 3 messages, got %d", len(messages))
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
	b.Run("fit", func(b *testing.B) {
		msgs := readSessionMessages(path)
		raw, err := json.Marshal(msgs)
		if err != nil {
			b.Fatal(err)
		}
		payload := []byte(`{"type":"response","id":"m1","command":"get_messages","success":true,"data":{"messages":` +
			string(raw) + `}}`)
		s := &session{historyLimits: map[string]int{}}
		b.SetBytes(int64(len(payload)))
		for i := 0; i < b.N; i++ {
			s.wantHistory("m1", 40)
			s.fitHistory(payload)
		}
	})
	b.Run("incremental", func(b *testing.B) {
		for i := 0; i < b.N; i++ {
			s.cachedMessages(path) // warm cache, then one new line
			writeSessionFile(nil, path, []string{`{"type":"message","message":{"role":"user","content":"x"}}`})
		}
	})
}

func historyEntries(roles ...string) []json.RawMessage {
	out := make([]json.RawMessage, 0, len(roles))
	for i, role := range roles {
		out = append(out, json.RawMessage(
			`{"role":"`+role+`","content":"`+strconv.Itoa(i)+`"}`,
		))
	}
	return out
}

func TestClipHistoryKeepsTheNewestMessages(t *testing.T) {
	all := historyEntries("user", "assistant", "user", "assistant")
	window, start := clipHistory(all, 2)
	if start != 2 {
		t.Fatalf("start: want 2, got %d", start)
	}
	if len(window) != 2 || messageRole(window[0]) != "user" {
		t.Fatalf("window: want [user assistant], got %d entries starting with %q",
			len(window), messageRole(window[0]))
	}
}

func TestClipHistoryStartsAtATurnBoundary(t *testing.T) {
	// The naive cut would land on a tool result; the page moves back to the
	// user message that opened the turn.
	all := historyEntries("user", "assistant", "user", "assistant",
		"toolResult", "toolResult", "assistant")
	window, start := clipHistory(all, 3)
	if start != 2 {
		t.Fatalf("start: want 2, got %d", start)
	}
	if len(window) != 5 {
		t.Fatalf("window: want 5, got %d", len(window))
	}
}

func TestClipHistoryLeavesShortTranscriptsAlone(t *testing.T) {
	all := historyEntries("user", "assistant")
	window, start := clipHistory(all, 40)
	if start != 0 || len(window) != 2 {
		t.Fatalf("want the whole transcript, got start=%d len=%d", start, len(window))
	}
}

func TestFitHistoryTrimsForwardedReply(t *testing.T) {
	s := &session{historyLimits: map[string]int{}}
	all := historyEntries("user", "assistant", "user", "assistant")
	raw, err := json.Marshal(all)
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte(`{"type":"response","id":"msgs-7","command":"get_messages","success":true,` +
		`"data":{"messages":` + string(raw) + `}}`)
	s.wantHistory("msgs-7", 2)
	out := s.fitHistory(payload)
	var env struct {
		Data struct {
			Messages     []json.RawMessage `json:"messages"`
			HistoryStart int               `json:"historyStart"`
			HistoryTotal int               `json:"historyTotal"`
		} `json:"data"`
	}
	if err := json.Unmarshal(out, &env); err != nil {
		t.Fatal(err)
	}
	if env.Data.HistoryStart != 2 || env.Data.HistoryTotal != 4 || len(env.Data.Messages) != 2 {
		t.Fatalf("want start=2 total=4 len=2, got start=%d total=%d len=%d",
			env.Data.HistoryStart, env.Data.HistoryTotal, len(env.Data.Messages))
	}
	// The limit is consumed: a reply for another id passes through untouched.
	if again := s.fitHistory(payload); !bytes.Equal(again, payload) {
		t.Fatal("a reply without a recorded limit must not be rewritten")
	}
}

func TestSyntheticMessagesPages(t *testing.T) {
	path := filepath.Join(t.TempDir(), "session.jsonl")
	entries := make([]string, 0, 12)
	for i := 0; i < 4; i++ {
		entries = append(entries,
			`{"type":"message","message":{"role":"user","content":"u`+strconv.Itoa(i)+`"}}`,
			`{"type":"message","message":{"role":"assistant","content":"a`+strconv.Itoa(i)+`"}}`,
			`{"type":"message","message":{"role":"toolResult","toolCallId":"c`+strconv.Itoa(i)+`","content":"t"}}`,
		)
	}
	writeSession(t, path, entries)
	s := &session{
		msgCache:      map[string]msgCacheEntry{},
		historyLimits: map[string]int{},
		activePath:    path,
	}
	// No agent attached: answered from the disk cache.
	s.wantHistory("m1", 3)
	reply := s.syntheticMessages("m1")
	data := reply["data"].(frame)
	window := data["messages"].([]json.RawMessage)
	start := data["historyStart"].(int)
	total := data["historyTotal"].(int)
	if total != 12 {
		t.Fatalf("total: want 12, got %d", total)
	}
	// The window is moved back to the user message that opened the turn.
	if messageRole(window[0]) != "user" {
		t.Fatalf("window must start at a user message, got %q", messageRole(window[0]))
	}
	if len(window) != total-start {
		t.Fatalf("len=%d does not match start=%d total=%d", len(window), start, total)
	}
	if start == 0 {
		t.Fatal("a 3-message page of 12 must report older messages")
	}
}
