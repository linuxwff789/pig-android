package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestTrackFrameState(t *testing.T) {
	s := newTestSession(t)
	path := filepath.Join(s.sessionsDir, "p", "s.jsonl")
	a, _ := testAgent(path)
	s.agents[path], s.active, s.activePath = a, a, path

	frames := []string{
		`{"type":"agent_start"}`,
		`{"type":"message_start","message":{"role":"assistant"}}`,
		`{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"hi"}}`,
		`{"type":"tool_execution_start","toolCallId":"t1","toolName":"bash"}`,
		`{"type":"tool_execution_update","toolCallId":"t1","partialResult":{"x":1}}`,
		`{"type":"extension_ui_request","id":"u1","method":"confirm"}`,
		`{"type":"extension_ui_request","method":"setWidget","widgetKey":"w","widgetLines":["line"]}`,
		`{"type":"extension_ui_request","method":"setStatus","statusKey":"s","statusText":"ok"}`,
		`{"type":"extension_ui_request","method":"setTitle","title":"T"}`,
	}
	for _, line := range frames {
		a.trackFrame(s, []byte(line))
	}
	if !a.isBusy() {
		t.Fatal("agent_start did not mark busy")
	}
	a.mu.Lock()
	if a.streamText != "hi" {
		t.Fatalf("streamText: %q", a.streamText)
	}
	if a.activeTools["t1"].Name != "bash" || len(a.activeTools["t1"].Partial) == 0 {
		t.Fatalf("tool progress: %#v", a.activeTools)
	}
	if len(a.pendingUI["u1"]) == 0 {
		t.Fatal("confirm request not remembered")
	}
	if len(a.widgets["w"]) == 0 || len(a.statuses["s"]) == 0 || len(a.title) == 0 {
		t.Fatalf("ui state: %#v %#v %#v", a.widgets, a.statuses, a.title)
	}
	a.mu.Unlock()

	a.trackFrame(s, []byte(`{"type":"message_end","message":{"role":"assistant"}}`))
	a.trackFrame(s, []byte(`{"type":"tool_execution_end","toolCallId":"t1"}`))
	a.trackFrame(s, []byte(`{"type":"error"}`))
	if a.isBusy() {
		t.Fatal("error did not clear busy")
	}
	a.mu.Lock()
	if a.streamText != "" || len(a.activeTools) != 0 {
		t.Fatalf("message/tool state: %q %#v", a.streamText, a.activeTools)
	}
	a.mu.Unlock()

	a.trackFrame(s, []byte(`{"type":"extension_ui_request","method":"setWidget","widgetKey":"w"}`))
	a.trackFrame(s, []byte(`{"type":"extension_ui_request","method":"setStatus","statusKey":"s","statusText":null}`))
	a.trackFrame(s, []byte(`{"type":"extension_ui_request","method":"setTitle","title":""}`))
	a.mu.Lock()
	if _, ok := a.widgets["w"]; ok {
		t.Fatal("empty widget did not clear")
	}
	if _, ok := a.statuses["s"]; ok {
		t.Fatal("empty status did not clear")
	}
	if a.title != nil {
		t.Fatal("empty title did not clear")
	}
	a.mu.Unlock()

	newPath := filepath.Join(s.sessionsDir, "p", "new.jsonl")
	a.trackFrame(s, []byte(`{"type":"response","command":"get_state","data":{"sessionFile":"`+newPath+`"}}`))
	if s.agents[newPath] != a || a.path != newPath || s.activePath != newPath {
		t.Fatalf("get_state path mapping: agents=%v path=%q active=%q", s.agents, a.path, s.activePath)
	}
	a.trackFrame(s, []byte(`not json`))
	a.trackFrame(s, []byte(`{}`))
}

func TestStartAgentWithFakePig(t *testing.T) {
	s := newTestSession(t)
	dir := t.TempDir()
	logPath := filepath.Join(dir, "pig.log")
	sessionPath := filepath.Join(s.sessionsDir, "p", "fake.jsonl")
	script := filepath.Join(dir, "pig")
	body := `#!/bin/sh
echo "$@" >> "$FAKE_LOG"
while IFS= read -r line; do
  echo "$line" >> "$FAKE_LOG"
  case "$line" in
    *'"type":"get_state"'*)
      printf '{"type":"response","id":"1","command":"get_state","success":true,"data":{"sessionFile":"%s"}}\n' "$FAKE_SESSION"
      ;;
    *'"type":"prompt"'*)
      printf '{"type":"message","message":{"role":"assistant"}}\n'
      ;;
  esac
done
`
	if err := os.WriteFile(script, []byte(body), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("FAKE_LOG", logPath)
	t.Setenv("FAKE_SESSION", sessionPath)
	s.pigBin = script
	s.cwd = dir
	m := &memConn{}
	s.conn, s.out, s.connGen = m, &connWriter{conn: m}, 1

	a, err := s.ensureAgent("", false)
	if err != nil || a == nil {
		t.Fatalf("ensureAgent: %v", err)
	}
	if s.activeAgent() != a || s.activePathValue() != "" {
		t.Fatal("new agent is not active")
	}

	s.handleAgentFrame([]byte(`{"type":"get_state","id":"1"}`), "get_state", "1", "")
	deadline := time.Now().Add(5 * time.Second)
	gotPath := false
	for time.Now().Before(deadline) && !gotPath {
		for _, frame := range m.frames(t) {
			if frame["type"] == "response" && frame["sessionPath"] == sessionPath {
				gotPath = true
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !gotPath {
		t.Fatal("get_state response/sessionPath not forwarded")
	}
	s.agentMu.Lock()
	mapped := s.agents[sessionPath]
	s.agentMu.Unlock()
	if mapped != a || s.activePathValue() != sessionPath {
		t.Fatalf("path not mapped: agent=%v active=%q", mapped, s.activePathValue())
	}

	s.handleAgentFrame([]byte(`{"type":"prompt","id":"p1"}`), "prompt", "p1", sessionPath)
	deadline = time.Now().Add(5 * time.Second)
	gotMessage := false
	for time.Now().Before(deadline) && !gotMessage {
		for _, frame := range m.frames(t) {
			if frame["type"] == "message" {
				gotMessage = true
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	if !gotMessage {
		t.Fatal("pig stdout not forwarded")
	}

	otherPath := filepath.Join(s.sessionsDir, "p", "other.jsonl")
	b, err := s.ensureAgent(otherPath, true)
	if err != nil || b == nil {
		t.Fatalf("ensureAgent(path): %v", err)
	}
	if b == a {
		t.Fatal("ensureAgent(path) reused the agent of another session")
	}
	if again, err := s.ensureAgent(otherPath, true); err != nil || again != b {
		t.Fatalf("ensureAgent(path) did not reuse the parked agent: %v %v", again, err)
	}
	time.Sleep(100 * time.Millisecond)
	logBody, _ := os.ReadFile(logPath)
	if !bytes.Contains(logBody, []byte("--session "+otherPath)) {
		t.Fatalf("--session not passed: %s", logBody)
	}

	s.stopAgent(b)
	s.stopAgent(a)
	if s.activeAgent() != nil {
		t.Fatal("stopAgent left an active agent")
	}

	// A missing binary and a closed install gate are reported, not retried.
	s.pigBin = filepath.Join(dir, "missing")
	if _, err := s.ensureAgent("", false); err == nil {
		t.Fatal("missing pig binary accepted")
	}
	s.pigBin = script
	s.install.reset()
	if _, err := s.startAgent("", false); err == nil || !strings.Contains(err.Error(), "install in progress") {
		t.Fatalf("install gate: %v", err)
	}
}

func TestSettingsReadWriteAndErrors(t *testing.T) {
	s, m := memSession(t)
	if err := os.MkdirAll(filepath.Dir(s.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(s.settingsPath, []byte(`{"compaction":{"enabled":false},"keep":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	handleSettings(s, "r1", nil)
	frame := m.frames(t)[0]
	if frame["type"] != "relay_settings" || len(frame["editable"].([]any)) == 0 {
		t.Fatalf("read settings: %#v", frame)
	}
	handleSettings(s, "w1", map[string]any{
		"compaction.enabled":       true,
		"compaction.reserveTokens": float64(512),
		"cacheWarming":             "bogus",
		"unknown.key":              "x",
	})
	if frame := m.frames(t)[0]; frame["type"] != "relay_settings" {
		t.Fatalf("write settings: %#v", frame)
	}
	root, _ := readSettingsFile(s.settingsPath)
	if root["keep"] != float64(1) {
		t.Fatalf("unknown key lost: %#v", root)
	}
	compaction := root["compaction"].(map[string]interface{})
	if compaction["enabled"] != true {
		t.Fatalf("enabled not written: %#v", compaction)
	}
	if compaction["reserveTokens"] != nil {
		t.Fatalf("below-min int accepted: %#v", compaction)
	}
	if root["cacheWarming"] != nil {
		t.Fatalf("bad enum accepted: %#v", root)
	}

	if err := os.WriteFile(s.settingsPath, []byte("{bad"), 0o600); err != nil {
		t.Fatal(err)
	}
	handleSettings(s, "e1", nil)
	if frame := m.frames(t)[0]; frame["type"] != "relay_settings_error" {
		t.Fatalf("bad settings: %#v", frame)
	}

	// A missing directory makes both the backup and the temp file fail.
	s.settingsPath = filepath.Join(s.cwd, "missing-dir", "settings.json")
	handleSettings(s, "e2", map[string]any{"compaction.enabled": true})
	if frame := m.frames(t)[0]; frame["type"] != "relay_settings_error" {
		t.Fatalf("unwritable settings: %#v", frame)
	}
}

func TestRestoreErrorPaths(t *testing.T) {
	s, m := memSession(t)
	// Bad base64 aborts and removes the staging file.
	s.beginRestore("r1", "x.tar.gz", 10)
	m.frames(t)
	s.restoreChunk("r1", "!!!")
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" {
		t.Fatalf("bad base64: %#v", frame)
	}

	// A chunk larger than the announced size is rejected.
	s.beginRestore("r2", "x.tar.gz", 2)
	m.frames(t)
	s.restoreChunk("r2", base64.StdEncoding.EncodeToString([]byte("abc")))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" || !strings.Contains(frame["message"].(string), "announced size") {
		t.Fatalf("oversize chunk: %#v", frame)
	}

	// A closed staging file makes the write fail.
	s.beginRestore("r3", "x.tar.gz", 10)
	m.frames(t)
	r := s.currentRestore()
	_ = r.file.Close()
	s.restoreChunk("r3", base64.StdEncoding.EncodeToString([]byte("abc")))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" || !strings.Contains(frame["message"].(string), "staging") {
		t.Fatalf("write failure: %#v", frame)
	}
}

func TestEndRestoreAndApplyErrors(t *testing.T) {
	s, m := memSession(t)
	s.beginRestore("e1", "x.tar.gz", 0)
	m.frames(t)
	s.endRestore("e1", "abc")
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" || !strings.Contains(frame["message"].(string), "empty") {
		t.Fatalf("empty archive: %#v", frame)
	}

	s.beginRestore("e2", "x.tar.gz", 5)
	m.frames(t)
	s.restoreChunk("e2", base64.StdEncoding.EncodeToString([]byte("abc")))
	s.endRestore("e2", "abc")
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" || !strings.Contains(frame["message"].(string), "incomplete") {
		t.Fatalf("incomplete: %#v", frame)
	}

	// Correct checksum but not a gzip: applyRestore reports the extract error.
	data := []byte("not gzip")
	sum := sha256Hex(data)
	s.beginRestore("e3", "x.tar.gz", int64(len(data)))
	m.frames(t)
	s.restoreChunk("e3", base64.StdEncoding.EncodeToString(data))
	s.endRestore("e3", sum)
	deadline := time.Now().Add(3 * time.Second)
	var got map[string]any
	for time.Now().Before(deadline) && got == nil {
		for _, frame := range m.frames(t) {
			if frame["type"] == "relay_restore_error" || frame["type"] == "relay_restore_done" {
				got = frame
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	if got == nil || got["type"] != "relay_restore_error" || !strings.Contains(got["message"].(string), "gzip") {
		t.Fatalf("apply error: %#v", got)
	}

	// An automatic-backup failure changes nothing and reports why.
	pigDir := filepath.Join(s.cwd, ".pig")
	if err := os.Chmod(pigDir, 0o500); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = os.Chmod(pigDir, 0o700) }()
	file, err := os.CreateTemp(filepath.Join(s.cwd), "staging")
	if err != nil {
		t.Fatal(err)
	}
	_, _ = file.Write(data)
	_ = file.Close()
	s.transfer.applyCancel.Store(false)
	s.applyRestore("e4", &restoreSession{path: file.Name(), file: file, written: int64(len(data))})
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" || !strings.Contains(frame["message"].(string), "automatic backup") {
		t.Fatalf("auto backup failure: %#v", frame)
	}
}

func TestAbandonTransfer(t *testing.T) {
	s, _ := memSession(t)
	dir := filepath.Join(s.cwd, ".pig", "tmp")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	file, err := os.CreateTemp(dir, "restore-*")
	if err != nil {
		t.Fatal(err)
	}
	s.transfer.connGen = 1
	s.transfer.applyGen = 1
	s.transfer.restore = &restoreSession{file: file, path: file.Name(), id: "r"}
	s.abandonTransfer(2) // newer client owns the transfer
	if s.transfer.restore == nil {
		t.Fatal("stale connection abandoned a newer transfer")
	}
	s.abandonTransfer(1)
	if s.transfer.restore != nil || !s.transfer.cancel.Load() || !s.transfer.applyCancel.Load() {
		t.Fatalf("abandon state: restore=%v cancel=%v applyCancel=%v",
			s.transfer.restore, s.transfer.cancel.Load(), s.transfer.applyCancel.Load())
	}
	if _, err := os.Stat(file.Name()); !os.IsNotExist(err) {
		t.Fatal("staging file survived abandon")
	}
}

func TestServeAuthFailures(t *testing.T) {
	hadClient.Store(true)
	authFailMu.Lock()
	authFailTimes = nil
	authFailMu.Unlock()
	s := newTestSession(t)
	s.token = "tok"

	for _, auth := range []string{
		`{"type":"relay_auth","token":"wrong"}`,
		`not json`,
		`{"type":"ping","token":"tok"}`,
	} {
		server, client := net.Pipe()
		go serve(server, s)
		reader := bufioNewReader(client)
		if _, err := client.Write([]byte(auth + "\n")); err != nil {
			t.Fatal(err)
		}
		if frame := readJSONFrame(t, reader); frame["type"] != "relay_error" {
			t.Fatalf("auth %q: %#v", auth, frame)
		}
		_ = client.Close()
	}
}

func bufioNewReader(conn net.Conn) *bufio.Reader { return bufio.NewReader(conn) }

func sha256Hex(data []byte) string {
	sum := sha256.Sum256(data)
	return hex.EncodeToString(sum[:])
}
