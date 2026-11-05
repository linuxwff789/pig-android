package main

import (
	"encoding/json"
	"path/filepath"
	"testing"
)

// The app locates a search hit by the message's session-file id. The envelope
// carries it, the message object usually does not, so scanMessages has to put
// it back or every "jump to hit" fails on history.
func TestScanMessagesCarriesEnvelopeID(t *testing.T) {
	s := newTestSession(t)
	path := filepath.Join(s.sessionsDir, "p", "a.jsonl")
	writeFile(t, path, `{"type":"message","id":"abc123","timestamp":"2026-11-05T10:16:38.890Z","message":{"role":"user","content":[{"type":"text","text":"hi"}]}}`+"\n")
	messages, err := scanMessages(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(messages) != 1 {
		t.Fatalf("messages = %d", len(messages))
	}
	var doc struct {
		ID        string `json:"id"`
		Timestamp string `json:"timestamp"`
		Role      string `json:"role"`
	}
	if err := json.Unmarshal(messages[0], &doc); err != nil {
		t.Fatal(err)
	}
	if doc.ID != "abc123" {
		t.Fatalf("id = %q, want the envelope id", doc.ID)
	}
	if doc.Role != "user" {
		t.Fatalf("role = %q", doc.Role)
	}
	if doc.Timestamp != "2026-11-05T10:16:38.890Z" {
		// The app derives both its row key and its locate fallback from this.
		t.Fatalf("timestamp = %q, want the envelope timestamp", doc.Timestamp)
	}
}

func TestScanMessagesKeepsInnerMessageID(t *testing.T) {
	s := newTestSession(t)
	path := filepath.Join(s.sessionsDir, "p", "a.jsonl")
	writeFile(t, path, `{"type":"message","id":"outer","message":{"id":"inner","role":"assistant","content":"x"}}`+"\n")
	messages, err := scanMessages(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		ID string `json:"id"`
	}
	if err := json.Unmarshal(messages[0], &doc); err != nil {
		t.Fatal(err)
	}
	if doc.ID != "inner" {
		t.Fatalf("id = %q, an id already on the message must win", doc.ID)
	}
}

func TestScanMessagesIgnoresNonMessageLines(t *testing.T) {
	s := newTestSession(t)
	path := filepath.Join(s.sessionsDir, "p", "a.jsonl")
	writeFile(t, path,
		`{"type":"session","id":"s"}`+"\n"+
			`{"type":"model_change","id":"m"}`+"\n"+
			`{"type":"message","id":"abc","message":{"role":"user","content":"hi"}}`+"\n")
	messages, err := scanMessages(path, 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(messages) != 1 {
		t.Fatalf("messages = %d, want just the message line", len(messages))
	}
}
