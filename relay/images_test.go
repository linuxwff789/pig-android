package main

import (
	"bufio"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func imageMessage(data string) json.RawMessage {
	return json.RawMessage(`{"role":"user","timestamp":"2026-01-01T00:00:00.000Z","content":[` +
		`{"type":"text","text":"look"},` +
		`{"type":"image","data":"` + data + `","mimeType":"image/png"}]}`)
}

func TestRewriteHistoryImagesStoresRefsByHash(t *testing.T) {
	s := newTestSession(t)
	raw := []byte("hello-image")
	data := base64.StdEncoding.EncodeToString(raw)
	sum := sha256.Sum256(raw)
	wantRef := hex.EncodeToString(sum[:])

	out := s.rewriteHistoryImages([]json.RawMessage{imageMessage(data)})
	if len(out) != 1 {
		t.Fatalf("messages = %d", len(out))
	}
	if strings.Contains(string(out[0]), `"data"`) {
		t.Fatalf("inline base64 survived: %s", out[0])
	}
	var doc struct {
		Content []struct {
			Type     string `json:"type"`
			Ref      string `json:"ref"`
			MimeType string `json:"mimeType"`
			Bytes    int    `json:"bytes"`
		} `json:"content"`
	}
	if err := json.Unmarshal(out[0], &doc); err != nil {
		t.Fatal(err)
	}
	var img *struct {
		Type     string `json:"type"`
		Ref      string `json:"ref"`
		MimeType string `json:"mimeType"`
		Bytes    int    `json:"bytes"`
	}
	for i := range doc.Content {
		if doc.Content[i].Type == "image" {
			img = &doc.Content[i]
		}
	}
	if img == nil || img.Ref != wantRef {
		t.Fatalf("image block = %+v, want ref %s", img, wantRef)
	}
	if img.Bytes != len(raw) || img.MimeType != "image/png" {
		t.Fatalf("metadata = %+v", img)
	}
	stored, err := os.ReadFile(filepath.Join(s.imageCacheDir(), wantRef))
	if err != nil || string(stored) != string(raw) {
		t.Fatalf("cache file: %v %q", err, stored)
	}
}

func TestRewriteHistoryImagesLeavesPlainMessagesByteIdentical(t *testing.T) {
	s := newTestSession(t)
	plain := json.RawMessage(`{"role":"assistant","content":"no picture here","usage":{"input":1}}`)
	out := s.rewriteHistoryImages([]json.RawMessage{plain})
	if string(out[0]) != string(plain) {
		t.Fatalf("message changed: %s", out[0])
	}
}

func TestSendImagesRejectsTraversalAndAnswersKnownRefs(t *testing.T) {
	s, reader := testSessionWithConn(t)
	raw := []byte("refbytes")
	sum := sha256.Sum256(raw)
	ref := hex.EncodeToString(sum[:])
	if err := os.MkdirAll(s.imageCacheDir(), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(s.imageCacheDir(), ref), raw, 0o600); err != nil {
		t.Fatal(err)
	}
	go s.sendImages("img-1", []string{"../../../etc/passwd", ref})
	frame := readJSONFrame(t, reader)
	if frame["type"] != "relay_image_data" {
		t.Fatalf("frame = %v", frame)
	}
	images, _ := frame["images"].([]any)
	if len(images) != 1 {
		t.Fatalf("images = %v", images)
	}
	entry, _ := images[0].(map[string]any)
	if entry["ref"] != ref {
		t.Fatalf("entry = %v", entry)
	}
	if got, err := base64.StdEncoding.DecodeString(entry["data"].(string)); err != nil || string(got) != string(raw) {
		t.Fatalf("data = %v %v", entry["data"], err)
	}
}

func TestValidImageRef(t *testing.T) {
	if !validImageRef(strings.Repeat("a", 64)) {
		t.Fatal("64 hex chars should be valid")
	}
	for _, bad := range []string{"", "../../etc/passwd", strings.Repeat("g", 64), strings.Repeat("a", 63)} {
		if validImageRef(bad) {
			t.Fatalf("%q accepted", bad)
		}
	}
}

func TestFitHistoryRefModeRewritesWindowOnly(t *testing.T) {
	s, _ := testSessionWithConn(t)
	path := filepath.Join(s.sessionsDir, "p", "s.jsonl")
	data := base64.StdEncoding.EncodeToString([]byte("img"))
	writeFile(t, path, `{"type":"message","message":`+string(imageMessage(data))+`}`+"\n")
	s.activePath = path
	s.wantHistory("h1", 10, "ref")
	payload := []byte(`{"type":"response","id":"h1","command":"get_messages","success":true,"data":{"messages":[]}}`)
	out := s.fitHistory(payload, path)
	if strings.Contains(string(out), `"data":"`+data) {
		t.Fatalf("base64 survived ref mode: %s", out)
	}
	if !strings.Contains(string(out), `"ref"`) {
		t.Fatalf("no ref in reply: %s", out)
	}
}

var _ = bufio.NewReader
