package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"testing"
)

func TestSyntheticMessagesReturnsWholeSession(t *testing.T) {
	s := newTestSession(t)
	path := filepath.Join(s.sessionsDir, "p", "long.jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	file, err := os.Create(path)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 2500; i++ {
		if _, err := fmt.Fprintf(file,
			`{"type":"message","message":{"role":"user","content":[{"type":"text","text":"m%d"}]}}`+"\n", i); err != nil {
			t.Fatal(err)
		}
	}
	_ = file.Close()
	s.activePath = path

	got := s.syntheticMessages("all")
	data := got["data"].(frame)
	messages := data["messages"].([]json.RawMessage)
	if len(messages) != 2500 || data["historyTotal"] != 2500 || data["historyStart"] != 0 {
		t.Fatalf("syntheticMessages: n=%d total=%v start=%v", len(messages), data["historyTotal"], data["historyStart"])
	}
	if all := readSessionMessages(path); len(all) != 2500 {
		t.Fatalf("readSessionMessages truncated: %d", len(all))
	}
}
