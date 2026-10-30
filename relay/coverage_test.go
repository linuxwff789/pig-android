package main

import (
	"archive/tar"
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"syscall"
	"testing"
	"time"
)

func TestLogHelpers(t *testing.T) {
	a, _ := testAgent("p")
	for i := 0; i < stderrTailMax+5; i++ {
		a.rememberStderr(string(rune('a' + i%26)))
	}
	if got := a.stderrTail(); len(got) != stderrTailMax {
		t.Fatalf("stderr tail: %d", len(got))
	}
	if got := a.lastStderr(); got != a.stderr[len(a.stderr)-1] {
		t.Fatalf("last stderr: %q", got)
	}
	empty, _ := testAgent("p")
	if empty.lastStderr() != "" || len(empty.stderrTail()) != 0 {
		t.Fatal("empty stderr tail")
	}

	authFailMu.Lock()
	authFailTimes = nil
	authFailMu.Unlock()
	for i := 0; i < 2; i++ {
		if noteAuthFailure() {
			t.Fatal("reported too early")
		}
	}
	if !noteAuthFailure() {
		t.Fatal("three failures not reported")
	}
	authFailMu.Lock()
	authFailTimes = nil
	authFailMu.Unlock()
}

func TestSessionStopAndAgentPaths(t *testing.T) {
	s, _ := memSession(t)
	a, _ := testAgent("/old/s.jsonl")
	s.agents[""], s.agents["/old/s.jsonl"] = a, a
	s.active, s.activePath = a, "/old/s.jsonl"

	s.mapAgentPath(a, "/new/s.jsonl")
	if s.agents["/new/s.jsonl"] != a || s.agents["/old/s.jsonl"] != nil || s.activePath != "/new/s.jsonl" {
		t.Fatalf("mapAgentPath: agents=%v activePath=%q", s.agents, s.activePath)
	}
	if !s.isActive(a) {
		t.Fatal("isActive")
	}
	s.mapAgentPath(a, "")
	if s.activePath != "/new/s.jsonl" {
		t.Fatal("empty path must be ignored")
	}

	s.stop()
	select {
	case <-s.stopped:
	default:
		t.Fatal("stop did not close stopped")
	}
	if s.currentOut() != nil || s.conn != nil {
		t.Fatal("stop left the connection attached")
	}
	a.mu.Lock()
	closed := a.pig.stdin == nil
	a.mu.Unlock()
	if !closed {
		t.Fatal("stop left pig stdin open")
	}
	s.stop() // idempotent
}

func TestParkAndReapAgents(t *testing.T) {
	s, _ := memSession(t)
	active, _ := testAgent("/active.jsonl")
	empty, _ := testAgent("/empty.jsonl")
	busy, _ := testAgent("/busy.jsonl")
	contentPath := filepath.Join(s.sessionsDir, "p", "content.jsonl")
	if err := os.MkdirAll(filepath.Dir(contentPath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(contentPath, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	withContent, _ := testAgent(contentPath)
	busy.busy = true
	s.agentMu.Lock()
	s.agents["/active.jsonl"] = active
	s.agents["/empty.jsonl"] = empty
	s.agents["/busy.jsonl"] = busy
	s.agents[contentPath] = withContent
	s.active, s.activePath = active, "/active.jsonl"
	s.agentMu.Unlock()

	s.stopEmptyParked()
	s.agentMu.Lock()
	emptyGone := s.agents["/empty.jsonl"] == nil
	busyKept := s.agents["/busy.jsonl"] == busy
	contentKept := s.agents[contentPath] == withContent
	s.agentMu.Unlock()
	if !emptyGone {
		t.Fatal("empty parked agent was not stopped")
	}
	if !busyKept || !contentKept {
		t.Fatal("stopEmptyParked removed a busy/live session")
	}

	promoted, _ := testAgent("/parked.jsonl")
	s.agentMu.Lock()
	s.agents["/parked.jsonl"] = promoted
	s.agentMu.Unlock()
	if got := s.promoteEmptyParked(); got != promoted {
		t.Fatalf("promoteEmptyParked: %v", got)
	}
	s.agentMu.Lock()
	active, activePath, promotedKey := s.active, s.activePath, s.agents[""]
	s.agentMu.Unlock()
	if active != promoted || promotedKey != promoted || activePath != "" {
		t.Fatalf("promote state: active=%v path=%q", active, activePath)
	}

	reap, _ := testAgent("/reap.jsonl")
	s.agentMu.Lock()
	s.agents["/reap.jsonl"] = reap
	s.active = promoted
	s.agentMu.Unlock()
	go s.reapAgent(reap)
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		s.agentMu.Lock()
		_, still := s.agents["/reap.jsonl"]
		s.agentMu.Unlock()
		if !still {
			break
		}
		time.Sleep(20 * time.Millisecond)
	}
	s.agentMu.Lock()
	_, still := s.agents["/reap.jsonl"]
	s.agentMu.Unlock()
	if still {
		t.Fatal("reapAgent did not stop the idle agent")
	}
}

func TestHandleModelOverride(t *testing.T) {
	s, m := memSession(t)
	if err := os.MkdirAll(filepath.Dir(s.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
	modelsPath := filepath.Join(filepath.Dir(s.settingsPath), "models.json")
	if err := os.WriteFile(modelsPath, []byte(`{"providers":{"openai":{"modelOverrides":{}}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	window := 128000
	handleModelOverride(s, "m1", "openai", "gpt", &window)
	frame := m.frames(t)[0]
	if frame["type"] != "relay_model_override" || frame["contextWindow"] != float64(window) {
		t.Fatalf("override: %#v", frame)
	}
	root, _ := readSettingsFile(modelsPath)
	providers := root["providers"].(map[string]interface{})
	overrides := providers["openai"].(map[string]interface{})["modelOverrides"].(map[string]interface{})
	if overrides["gpt"].(map[string]interface{})["contextWindow"] != float64(window) {
		t.Fatalf("models.json: %#v", root)
	}

	handleModelOverride(s, "m2", "openai", "gpt", nil)
	if frame := m.frames(t)[0]; frame["contextWindow"] != float64(0) {
		t.Fatalf("clear: %#v", frame)
	}
	root, _ = readSettingsFile(modelsPath)
	providers = root["providers"].(map[string]interface{})
	overrides = providers["openai"].(map[string]interface{})["modelOverrides"].(map[string]interface{})
	if _, ok := overrides["gpt"]; ok {
		t.Fatalf("override not cleared: %#v", overrides)
	}

	negative := -1
	for _, tc := range []struct {
		provider, model string
		window          *int
	}{
		{"", "gpt", &window},
		{"openai", "", &window},
		{"openai", "gpt", &negative},
		{"missing", "gpt", &window},
	} {
		handleModelOverride(s, "bad", tc.provider, tc.model, tc.window)
		if frame := m.frames(t)[0]; frame["type"] != "relay_model_override_error" {
			t.Fatalf("bad override accepted: %#v", frame)
		}
	}
	if err := os.WriteFile(modelsPath, []byte(`{"providers":{}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	handleModelOverride(s, "bad", "openai", "gpt", &window)
	if frame := m.frames(t)[0]; !strings.Contains(frame["message"].(string), "unknown provider") {
		t.Fatalf("unknown provider: %#v", frame)
	}
}

func TestWatchUIPushesChanges(t *testing.T) {
	s, m := memSession(t)
	s.uiDir = t.TempDir()
	if err := os.WriteFile(filepath.Join(s.uiDir, "manifest.json"), []byte(`{"name":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	go s.watchUI()
	defer close(s.stopped)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		for _, frame := range m.frames(t) {
			if frame["type"] == "relay_ui_pack" && frame["hash"] != "" {
				return
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("watchUI did not push the pack")
}

func TestBackupStreamAndCancel(t *testing.T) {
	s, m := memSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	if err := os.MkdirAll(filepath.Join(agentDir, "sessions", "p"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(agentDir, "settings.json"), []byte(`{"packages":[]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(agentDir, "sessions", "p", "s.jsonl"), []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(s.cwd, ".pig", "state"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(s.cwd, ".pig", "state", "x"), []byte("state"), 0o600); err != nil {
		t.Fatal(err)
	}
	s.uiDir = t.TempDir()
	if err := os.WriteFile(filepath.Join(s.uiDir, "manifest.json"), []byte(`{"name":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}

	s.startBackup("b1", true, true)
	var start, done map[string]any
	var raw []byte
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) && done == nil {
		for _, frame := range m.frames(t) {
			switch frame["type"] {
			case "relay_backup_start":
				start = frame
			case "relay_backup_chunk":
				data, err := base64.StdEncoding.DecodeString(frame["data"].(string))
				if err != nil {
					t.Fatal(err)
				}
				raw = append(raw, data...)
			case "relay_backup_done":
				done = frame
			case "relay_backup_error":
				t.Fatalf("backup error: %#v", frame)
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	if start == nil || done == nil {
		t.Fatalf("backup incomplete: start=%v done=%v", start, done)
	}
	sum := sha256.Sum256(raw)
	if done["sha256"] != hex.EncodeToString(sum[:]) {
		t.Fatalf("checksum mismatch: %v vs %s", done["sha256"], hex.EncodeToString(sum[:]))
	}
	gz, err := gzip.NewReader(bytes.NewReader(raw))
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]bool{}
	tr := tar.NewReader(gz)
	for {
		hdr, err := tr.Next()
		if err != nil {
			break
		}
		names[hdr.Name] = true
	}
	if !names["agent/settings.json"] || !names["agent/sessions/p/s.jsonl"] || !names[".pig/state/x"] {
		t.Fatalf("archive entries: %v", names)
	}

	// A transfer already in progress is refused. Wait for the streaming
	// goroutine's deferred finishTransfer before taking the lock.
	deadline = time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		s.transfer.mu.Lock()
		busy := s.transfer.busy
		s.transfer.mu.Unlock()
		if !busy {
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	s.transfer.mu.Lock()
	s.transfer.busy = true
	s.transfer.mu.Unlock()
	s.startBackup("b2", false, false)
	if frame := m.frames(t)[0]; frame["type"] != "relay_backup_error" {
		t.Fatalf("busy backup: %#v", frame)
	}
	s.transfer.mu.Lock()
	s.transfer.busy = false
	s.transfer.mu.Unlock()

	// A pre-set cancel flag stops before any data is written.
	cancel := &atomic.Bool{}
	cancel.Store(true)
	s.streamBackup("b3", true, true, cancel)
	frames := m.frames(t)
	if len(frames) != 2 || frames[0]["type"] != "relay_backup_start" ||
		frames[1]["type"] != "relay_backup_error" || frames[1]["cancelled"] != true {
		t.Fatalf("cancelled backup: %#v", frames)
	}

	buf := make([]byte, 4)
	n, err := zeroReader{}.Read(buf)
	if err != nil || n != len(buf) || !bytes.Equal(buf, []byte{0, 0, 0, 0}) {
		t.Fatalf("zeroReader: %d %v %v", n, err, buf)
	}
}

func TestAbortRestoreAndStopAllAgents(t *testing.T) {
	s, m := memSession(t)
	dir := filepath.Join(s.cwd, ".pig", "tmp")
	if err := os.MkdirAll(dir, 0o700); err != nil {
		t.Fatal(err)
	}
	file, err := os.CreateTemp(dir, "restore-*.tar.gz")
	if err != nil {
		t.Fatal(err)
	}
	path := file.Name()
	s.transfer.restore = &restoreSession{file: file, path: path, id: "r1"}
	s.transfer.busy = true
	s.abortRestore("r1", "boom")
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" || frame["message"] != "boom" {
		t.Fatalf("abortRestore: %#v", frame)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("staging file survived abort")
	}
	s.transfer.mu.Lock()
	busy := s.transfer.busy
	s.transfer.mu.Unlock()
	if busy {
		t.Fatal("abortRestore left the transfer busy")
	}

	a, _ := testAgent("/a.jsonl")
	b, _ := testAgent("/b.jsonl")
	s.agents["/a.jsonl"], s.agents["/b.jsonl"] = a, b
	s.active = a
	s.stopAllAgents()
	if len(s.agents) != 0 || s.active != nil {
		t.Fatalf("stopAllAgents: %v", s.agents)
	}
	a.mu.Lock()
	closed := a.pig.stdin == nil
	a.mu.Unlock()
	if !closed {
		t.Fatal("stopAllAgents left stdin open")
	}
}

func TestProviderCRUD(t *testing.T) {
	s, m := memSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	if err := os.MkdirAll(agentDir, 0o700); err != nil {
		t.Fatal(err)
	}
	configPath, cachePath, modelsPath := s.providerPaths()
	if err := os.WriteFile(configPath, []byte(`{"providers":{"p1":{"id":"p1","baseUrl":"http://127.0.0.1:1","api":"openai"}}}`), 0o600); err != nil {
		t.Fatal(err)
	}

	s.sendProviders("list")
	frame := m.frames(t)[0]
	if frame["type"] != "relay_providers" || len(frame["providers"].([]any)) != 1 {
		t.Fatalf("sendProviders: %#v", frame)
	}

	s.deleteProvider("d0", map[string]any{})
	if frame := m.frames(t)[0]; frame["type"] != "relay_provider_error" {
		t.Fatalf("delete without id: %#v", frame)
	}
	s.deleteProvider("d1", map[string]any{"id": "p1"})
	if frame := m.frames(t)[0]; frame["type"] != "relay_provider_deleted" {
		t.Fatalf("delete: %#v", frame)
	}
	if store, _ := readJSONObjectFile(configPath); len(providerMapOf(store)) != 0 {
		t.Fatalf("provider still stored: %#v", store)
	}
	_ = os.WriteFile(cachePath, []byte(`{"p1":{}}`), 0o600)

	s.deleteProvider("d2", map[string]any{"id": "p1"})
	if frame := m.frames(t)[0]; frame["type"] != "relay_provider_error" || !strings.Contains(frame["message"].(string), "没有自定义供应商") {
		t.Fatalf("missing provider: %#v", frame)
	}
	if err := os.WriteFile(modelsPath, []byte(`{"providers":{"p2":{}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.deleteProvider("d3", map[string]any{"id": "p2"})
	if frame := m.frames(t)[0]; !strings.Contains(frame["message"].(string), "models.json") {
		t.Fatalf("models.json hint: %#v", frame)
	}

	t.Setenv("PIG_OFFLINE", "1")
	s.sendProviderModels("r0", map[string]any{"id": "p1", "baseUrl": "http://127.0.0.1:1"})
	if frame := m.frames(t)[0]; frame["type"] != "relay_provider_error" || !strings.Contains(frame["message"].(string), "离线") {
		t.Fatalf("offline refresh: %#v", frame)
	}
	t.Setenv("PIG_OFFLINE", "")
	s.sendProviderModels("r1", map[string]any{"id": "p1"})
	if frame := m.frames(t)[0]; !strings.Contains(frame["message"].(string), "Base URL") {
		t.Fatalf("empty base URL: %#v", frame)
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/models" {
			t.Errorf("path: %s", r.URL.Path)
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"b"},{"id":"a"}]}`))
	}))
	defer server.Close()
	s.sendProviderModels("r2", map[string]any{"id": "p1", "baseUrl": server.URL, "api": "openai"})
	frame = m.frames(t)[0]
	if frame["type"] != "relay_provider_models" {
		t.Fatalf("refresh: %#v", frame)
	}
	models := frame["models"].([]any)
	if len(models) != 2 || providerString(models[0].(map[string]any), "id") != "a" {
		t.Fatalf("models: %#v", models)
	}
}

func TestSettingsPackagesAndEmbeddedInstall(t *testing.T) {
	dir := t.TempDir()
	settings := filepath.Join(dir, "settings.json")
	if err := os.WriteFile(settings, []byte(`{"packages":["  a  ","","b"]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := settingsPackages(settings); len(got) != 2 || got[0] != "a" || got[1] != "b" {
		t.Fatalf("settingsPackages: %v", got)
	}
	if got := settingsPackages(filepath.Join(dir, "missing.json")); got != nil {
		t.Fatalf("missing settings: %v", got)
	}
	if err := os.WriteFile(settings, []byte(`{bad`), 0o600); err != nil {
		t.Fatal(err)
	}
	if got := settingsPackages(settings); got != nil {
		t.Fatalf("bad settings: %v", got)
	}

	logPath := filepath.Join(dir, "pig.log")
	script := filepath.Join(dir, "pig")
	if err := os.WriteFile(script, []byte("#!/bin/sh\necho \"$@\" >> \""+logPath+"\"\nexit 0\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	s := newTestSession(t)
	s.extReady = make(chan struct{})
	s.pigBin = script
	s.cwd = dir
	s.settingsPath = filepath.Join(dir, "agent", "settings.json")
	if err := os.MkdirAll(filepath.Dir(s.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
	s.installEmbeddedExtensions()
	select {
	case <-s.extReady:
	default:
		t.Fatal("installEmbeddedExtensions did not close extReady")
	}
	target := filepath.Join(dir, "agent", "extensions", "plan")
	if _, err := os.Stat(filepath.Join(target, embeddedPlanHash)); err != nil {
		t.Fatalf("embedded plan not extracted: %v", err)
	}
	body, err := os.ReadFile(logPath)
	if err != nil || !strings.Contains(string(body), "install "+target) {
		t.Fatalf("pig install was not called: %q %v", body, err)
	}

	failScript := filepath.Join(dir, "pig-fail")
	if err := os.WriteFile(failScript, []byte("#!/bin/sh\nexit 3\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := runPigInstall(failScript, target); err == nil {
		t.Fatal("runPigInstall ignored the exit code")
	}

	// A user-configured plan package elsewhere wins over the embedded copy.
	other := newTestSession(t)
	other.settingsPath = filepath.Join(dir, "other", "settings.json")
	if err := os.MkdirAll(filepath.Dir(other.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(other.settingsPath, []byte(`{"packages":["/some/plan"]}`), 0o600); err != nil {
		t.Fatal(err)
	}
	other.installEmbeddedPlan(filepath.Join(dir, "other"))
	if _, err := os.Stat(filepath.Join(dir, "other", "extensions", "plan")); !os.IsNotExist(err) {
		t.Fatal("embedded plan written despite a configured copy")
	}
}

func TestServeHandshake(t *testing.T) {
	hadClient.Store(true)
	s := newTestSession(t)
	s.token = "tok"
	s.uiDir = t.TempDir()
	if err := os.WriteFile(filepath.Join(s.uiDir, "manifest.json"), []byte(`{"name":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	server, client := net.Pipe()
	go serve(server, s)
	reader := bufio.NewReader(client)
	if _, err := client.Write([]byte(`{"type":"relay_auth","token":"tok"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_hello" {
		t.Fatalf("hello: %#v", frame)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_logs" {
		t.Fatalf("logs: %#v", frame)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_ui_pack" {
		t.Fatalf("ui pack: %#v", frame)
	}
	if _, err := client.Write([]byte(`{"type":"relay_ping","id":"p1"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_pong" {
		t.Fatalf("pong: %#v", frame)
	}
	if _, err := client.Write([]byte("{bad\n")); err != nil {
		t.Fatal(err)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_error" {
		t.Fatalf("bad json: %#v", frame)
	}
	_ = client.Close()
}

func TestLookupHostAndProjects(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
	defer cancel()
	if addrs, err := lookupHost(ctx, "localhost"); err != nil || len(addrs) == 0 {
		t.Fatalf("lookupHost: %v", err)
	}
	t.Setenv("PIG_DNS", "127.0.0.1:1")
	ctx2, cancel2 := context.WithCancel(context.Background())
	cancel2()
	if addrs, err := lookupHost(ctx2, "does-not-exist.invalid"); err == nil {
		t.Fatalf("lookupHost accepted an unresolvable host: %v", addrs)
	}

	root := t.TempDir()
	for _, name := range []string{"b", "a", ".hidden"} {
		if err := os.Mkdir(filepath.Join(root, name), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(filepath.Join(root, "file"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	projects := listProjects(root)
	if len(projects) != 2 || projects[0]["name"] != "a" || projects[1]["name"] != "b" {
		t.Fatalf("listProjects: %v", projects)
	}
	if got := listProjects(filepath.Join(root, "missing")); got != nil {
		t.Fatalf("missing root: %v", got)
	}
}

func TestAppendAndDeleteSession(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "p", "s.jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"type":"session","id":"parent"}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := appendSessionInfo(path, "  my\nname  ", dir); err != nil {
		t.Fatal(err)
	}
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Contains(body, []byte(`"name":"my name"`)) || !bytes.Contains(body, []byte(`"parentId":"parent"`)) {
		t.Fatalf("session_info: %s", body)
	}
	if err := appendSessionInfo(filepath.Join(dir, "outside.jsonl"), "x", dir); err == nil {
		t.Fatal("append outside sessions accepted")
	}
	if err := deleteSession(path, dir); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("deleteSession did not remove the file")
	}
	if err := deleteSession(filepath.Join(dir, "outside.jsonl"), dir); err == nil {
		t.Fatal("delete outside sessions accepted")
	}
	if err := deleteSession(filepath.Join(dir, "p", "missing.jsonl"), dir); err == nil {
		t.Fatal("delete accepted a missing file")
	}
}

func TestHandleClientBranches(t *testing.T) {
	s, m := memSession(t)
	s.handleClientLine([]byte(`{`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_error" {
		t.Fatalf("invalid json: %#v", frame)
	}

	s.handleClientLine([]byte(`{"type":"relay_restart","id":"r1"}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restarted" || frame["pid"] != float64(0) {
		t.Fatalf("restart: %#v", frame)
	}

	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "proj"), 0o700); err != nil {
		t.Fatal(err)
	}
	s.handleClientLine([]byte(`{"type":"relay_projects","id":"pr","root":` + strconvQuote(root) + `}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_projects" || len(frame["projects"].([]any)) != 1 {
		t.Fatalf("projects: %#v", frame)
	}

	unsavedPath := filepath.Join(s.sessionsDir, "p", "unsaved.jsonl")
	a, _ := testAgent(unsavedPath)
	s.agents[unsavedPath] = a
	s.handleClientLine([]byte(`{"type":"relay_sessions","id":"ls"}`))
	frame := m.frames(t)[0]
	if frame["type"] != "relay_sessions" || len(frame["sessions"].([]any)) != 1 {
		t.Fatalf("sessions: %#v", frame)
	}
	if item := frame["sessions"].([]any)[0].(map[string]any); item["unsaved"] != true {
		t.Fatalf("unsaved session: %#v", item)
	}

	s.uiDir = t.TempDir()
	if err := os.WriteFile(filepath.Join(s.uiDir, "manifest.json"), []byte(`{"name":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	s.handleClientLine([]byte(`{"type":"relay_ui","id":"u1"}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_ui_pack" {
		t.Fatalf("ui: %#v", frame)
	}

	s.handleClientLine([]byte(`{"type":"relay_unknown","id":"x"}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_error" {
		t.Fatalf("unknown: %#v", frame)
	}
	s.handleClientLine([]byte(`{"type":"relay_stop"}`))
}

func strconvQuote(value string) string {
	data, _ := json.Marshal(value)
	return string(data)
}

func TestProviderRunShell(t *testing.T) {
	if got := providerRunShell("printf '  hi  '"); got != "hi" {
		t.Fatalf("providerRunShell: %q", got)
	}
	if got := providerRunShell("exit 7"); got != "" {
		t.Fatalf("failed command returned %q", got)
	}
}

// TestMainHelper runs the real main() in a subprocess: main registers flags and
// may block on a listener, so it cannot be called from the test process.
func TestMainHelper(t *testing.T) {
	switch os.Getenv("RELAY_MAIN_MODE") {
	case "":
		t.Skip("helper process only")
	case "version":
		os.Args = []string{"pig-relay", "-version"}
	case "prepare":
		os.Args = []string{
			"pig-relay", "-prepare",
			"-cwd", os.Getenv("RELAY_MAIN_DIR"),
			"-ui-dir", os.Getenv("RELAY_MAIN_UI"),
			"-pig", os.Getenv("RELAY_MAIN_PIG"),
		}
	case "serve":
		os.Args = []string{
			"pig-relay",
			"-listen", "127.0.0.1",
			"-port", os.Getenv("RELAY_MAIN_PORT"),
			"-token", "tok",
			"-cwd", os.Getenv("RELAY_MAIN_DIR"),
			"-ui-dir", os.Getenv("RELAY_MAIN_UI"),
			"-pig", os.Getenv("RELAY_MAIN_PIG"),
		}
	default:
		t.Fatalf("unknown mode %q", os.Getenv("RELAY_MAIN_MODE"))
	}
	main()
}

func helperProcess(t *testing.T, mode string, env ...string) (string, error) {
	t.Helper()
	cmd := exec.Command(os.Args[0], "-test.run=TestMainHelper", "-test.v=false")
	if dir := os.Getenv("PIG_GOCOVERDIR"); dir != "" {
		cmd.Args = append(cmd.Args, "-test.gocoverdir="+dir)
	}
	cmd.Env = append(os.Environ(), append([]string{"RELAY_MAIN_MODE=" + mode}, env...)...)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func TestMainVersionAndPrepare(t *testing.T) {
	out, err := helperProcess(t, "version")
	if err != nil || !strings.Contains(out, buildVersion) {
		t.Fatalf("version: %q %v", out, err)
	}

	dir := t.TempDir()
	logPath := filepath.Join(dir, "pig.log")
	script := filepath.Join(dir, "pig")
	if err := os.WriteFile(script, []byte("#!/bin/sh\necho \"$@\" >> \""+logPath+"\"\nexit 0\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	ui := filepath.Join(dir, "ui")
	out, err = helperProcess(t, "prepare",
		"RELAY_MAIN_DIR="+dir, "RELAY_MAIN_UI="+ui, "RELAY_MAIN_PIG="+script)
	if err != nil || !strings.Contains(out, "prepared") {
		t.Fatalf("prepare: %q %v", out, err)
	}
	if _, err := os.Stat(filepath.Join(ui, "manifest.json")); err != nil {
		t.Fatalf("prepare did not seed the UI: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dir, ".pig", "agent", "extensions", "plan", embeddedPlanHash)); err != nil {
		t.Fatalf("prepare did not install the plan extension: %v", err)
	}
}

func TestMainServesAndStops(t *testing.T) {
	dir := t.TempDir()
	script := filepath.Join(dir, "pig")
	if err := os.WriteFile(script, []byte("#!/bin/sh\ncat >/dev/null\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	_ = listener.Close()

	cmd := exec.Command(os.Args[0], "-test.run=TestMainHelper", "-test.v=false")
	cmd.Env = append(os.Environ(),
		"RELAY_MAIN_MODE=serve",
		"RELAY_MAIN_PORT="+strconv.Itoa(port),
		"RELAY_MAIN_DIR="+dir,
		"RELAY_MAIN_UI="+filepath.Join(dir, "ui"),
		"RELAY_MAIN_PIG="+script,
	)
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = cmd.Process.Kill() }()

	addr := "127.0.0.1:" + strconv.Itoa(port)
	var conn net.Conn
	deadline := time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		conn, err = net.DialTimeout("tcp", addr, 300*time.Millisecond)
		if err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if conn == nil {
		t.Fatalf("relay did not listen: %v", err)
	}
	defer conn.Close()
	reader := bufio.NewReader(conn)
	if _, err := conn.Write([]byte(`{"type":"relay_auth","token":"tok"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_hello" {
		t.Fatalf("hello: %#v", frame)
	}
	if _, err := conn.Write([]byte(`{"type":"relay_ping","id":"p"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	// The installer/UI watcher may push relay_log frames between attach and
	// the pong; drain until the frame we asked for arrives.
	for i := 0; i < 20; i++ {
		frame := readJSONFrame(t, reader)
		if frame["type"] == "relay_pong" {
			break
		}
		if i == 19 {
			t.Fatalf("no pong (last: %#v)", frame)
		}
	}

	_ = cmd.Process.Signal(syscall.SIGTERM)
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("relay exited with %v", err)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("relay did not stop after SIGTERM")
	}
}
