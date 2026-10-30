package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestFitHistoryMergesDiskUnderPigTail(t *testing.T) {
	s := newTestSession(t)
	path := filepath.Join(s.sessionsDir, "p", "s.jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	var lines string
	for i := 0; i < 4; i++ {
		lines += fmt.Sprintf(`{"type":"message","message":{"role":"user","content":[{"type":"text","text":"m%d"}]}}`+"\n", i)
	}
	if err := os.WriteFile(path, []byte(lines), 0o600); err != nil {
		t.Fatal(err)
	}
	s.activePath = path

	// pig only remembers the last two messages of the file.
	payload := `{"type":"response","id":"h1","command":"get_messages","success":true,"data":{"messages":[` +
		`{"role":"user","content":[{"type":"text","text":"m2"}]},` +
		`{"role":"user","content":[{"type":"text","text":"m3"}]}]}}`
	s.wantHistory("h1", 10)
	out := s.fitHistory([]byte(payload))
	var env struct {
		Data struct {
			Messages     []json.RawMessage `json:"messages"`
			HistoryTotal int               `json:"historyTotal"`
		} `json:"data"`
	}
	if err := json.Unmarshal(out, &env); err != nil {
		t.Fatal(err)
	}
	if len(env.Data.Messages) != 4 || env.Data.HistoryTotal != 4 {
		t.Fatalf("merged history: n=%d total=%d", len(env.Data.Messages), env.Data.HistoryTotal)
	}

	// A live tail message that is not on disk is appended after the file.
	live := `{"type":"response","id":"h2","command":"get_messages","success":true,"data":{"messages":[` +
		`{"role":"user","content":[{"type":"text","text":"m3"}]},` +
		`{"role":"assistant","content":[{"type":"text","text":"live"}]}]}}`
	s.wantHistory("h2", 10)
	out = s.fitHistory([]byte(live))
	if err := json.Unmarshal(out, &env); err != nil {
		t.Fatal(err)
	}
	if len(env.Data.Messages) != 5 || env.Data.HistoryTotal != 5 {
		t.Fatalf("live merge: n=%d total=%d", len(env.Data.Messages), env.Data.HistoryTotal)
	}
}
