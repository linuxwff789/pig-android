package main

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func TestHandleClientDispatch(t *testing.T) {
	s, m := memSession(t)
	project := filepath.Join(s.sessionsDir, "p")
	if err := os.MkdirAll(project, 0o700); err != nil {
		t.Fatal(err)
	}
	sessionFile := filepath.Join(project, "d.jsonl")
	if err := os.WriteFile(sessionFile, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	busy, _ := testAgent(filepath.Join(project, "busy.jsonl"))
	busy.busy = true
	s.agents[busy.path], s.active, s.activePath = busy, busy, busy.path
	s.handleClientLine([]byte(`{"type":"relay_restart","id":"rb"}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_error" || !strings.Contains(frame["message"].(string), "turn") {
		t.Fatalf("busy restart: %#v", frame)
	}
	s.agents = map[string]*agentProc{}
	s.active = nil
	s.activePath = ""

	s.handleClientLine([]byte(`{"type":"relay_session_rename","id":"rn","path":` + quote(sessionFile) + `,"name":"New name"}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_session_renamed" {
		t.Fatalf("rename: %#v", frame)
	}
	s.handleClientLine([]byte(`{"type":"relay_session_rename","id":"rn2","path":"/etc/passwd","name":"x"}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_session_error" {
		t.Fatalf("bad rename: %#v", frame)
	}

	s.handleClientLine([]byte(`{"type":"relay_session_archive","id":"ar","path":` + quote(sessionFile) + `}`))
	frame := m.frames(t)[0]
	if frame["type"] != "relay_session_archived" {
		t.Fatalf("archive: %#v", frame)
	}
	archived := frame["archived"].(string)
	s.handleClientLine([]byte(`{"type":"relay_session_restore","id":"rs","path":` + quote(archived) + `}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_session_restored" {
		t.Fatalf("restore: %#v", frame)
	}
	s.handleClientLine([]byte(`{"type":"relay_session_archive","id":"ar2","path":` + quote(sessionFile) + `}`))
	archived = m.frames(t)[0]["archived"].(string)
	s.handleClientLine([]byte(`{"type":"relay_session_purge","id":"pu","path":` + quote(archived) + `}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_session_purged" {
		t.Fatalf("purge: %#v", frame)
	}
	if err := os.WriteFile(sessionFile, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	s.handleClientLine([]byte(`{"type":"relay_session_delete","id":"de","path":` + quote(sessionFile) + `}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_session_deleted" {
		t.Fatalf("delete: %#v", frame)
	}

	if err := os.WriteFile(sessionFile, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	s.handleClientLine([]byte(`{"type":"relay_backup","id":"b1","sessions":false,"ui":false}`))
	deadline := time.Now().Add(5 * time.Second)
	var done map[string]any
	for time.Now().Before(deadline) && done == nil {
		for _, frame := range m.frames(t) {
			if frame["type"] == "relay_backup_done" {
				done = frame
			}
			if frame["type"] == "relay_backup_error" {
				t.Fatalf("backup: %#v", frame)
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	if done == nil {
		t.Fatal("backup dispatch did not finish")
	}
	s.handleClientLine([]byte(`{"type":"relay_backup_cancel","id":"b1"}`))

	s.handleClientLine([]byte(`{"type":"relay_restore_begin","id":"r0","size":` + itoa64(backupMaxBytes+1) + `}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" {
		t.Fatalf("restore too large: %#v", frame)
	}

	s.handleClientLine([]byte(`{"type":"relay_provider_save","id":"ps","entry":{"id":"p1","baseUrl":"http://example.com","api":"openai-completions","auto":false,"models":[{"id":"m1"}]}}`))
	deadline = time.Now().Add(3 * time.Second)
	var saved map[string]any
	for time.Now().Before(deadline) && saved == nil {
		for _, frame := range m.frames(t) {
			if frame["type"] == "relay_provider_saved" || frame["type"] == "relay_provider_error" {
				saved = frame
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	if saved == nil || saved["type"] != "relay_provider_saved" {
		t.Fatalf("provider save: %#v", saved)
	}
	s.handleClientLine([]byte(`{"type":"relay_provider_delete","id":"pd","entry":{"id":"p1"}}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_provider_deleted" {
		t.Fatalf("provider delete: %#v", frame)
	}

	t.Setenv("PIG_OFFLINE", "1")
	s.handleClientLine([]byte(`{"type":"relay_provider_models","id":"pm","entry":{"id":"p1","baseUrl":"http://example.com"}}`))
	deadline = time.Now().Add(2 * time.Second)
	var refreshed map[string]any
	for time.Now().Before(deadline) && refreshed == nil {
		for _, frame := range m.frames(t) {
			if frame["type"] == "relay_provider_error" {
				refreshed = frame
			}
		}
		time.Sleep(10 * time.Millisecond)
	}
	if refreshed == nil || !strings.Contains(refreshed["message"].(string), "离线") {
		t.Fatalf("provider models: %#v", refreshed)
	}
	t.Setenv("PIG_OFFLINE", "")

	if err := os.WriteFile(filepath.Join(filepath.Dir(s.settingsPath), "models.json"),
		[]byte(`{"providers":{"openai":{"modelOverrides":{}}}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.handleClientLine([]byte(`{"type":"relay_model_override","id":"mo","provider":"openai","model":"gpt","contextWindow":4096}`))
	if frame := m.frames(t)[0]; frame["type"] != "relay_model_override" {
		t.Fatalf("model override: %#v", frame)
	}
	s.handleClientLine([]byte(`{"type":"relay_stop"}`))
}

func itoa64(value int64) string {
	return strconv.FormatInt(value, 10)
}

func quote(value string) string {
	data, _ := json.Marshal(value)
	return string(data)
}

func TestFetchProviderModelsBranches(t *testing.T) {
	if _, err := fetchProviderModels(map[string]any{}); err == nil {
		t.Fatal("empty base URL accepted")
	}

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/v1/models":
			if got := r.Header.Get("Authorization"); got != "Bearer secret" {
				t.Errorf("authorization: %q", got)
			}
			_, _ = w.Write([]byte(`{"data":[{"id":"m1"}]}`))
		case "/anthropic/models":
			if r.Header.Get("x-api-key") != "secret" || r.Header.Get("anthropic-version") == "" {
				t.Errorf("anthropic headers: %v", r.Header)
			}
			_, _ = w.Write([]byte(`{"models":[{"id":"claude"}]}`))
		case "/google/models":
			if r.URL.Query().Get("key") != "secret" {
				t.Errorf("google key: %q", r.URL.RawQuery)
			}
			_, _ = w.Write([]byte(`{"models":[{"name":"gemini"}]}`))
		case "/custom":
			if r.Header.Get("X-Test") != "ok" {
				t.Errorf("custom header: %v", r.Header)
			}
			_, _ = w.Write([]byte(`{"items":[{"id":"custom"}]}`))
		case "/empty":
			_, _ = w.Write([]byte(`{"data":[]}`))
		case "/badjson":
			_, _ = w.Write([]byte(`{`))
		case "/error":
			w.WriteHeader(500)
			_, _ = w.Write([]byte(`boom ?key=leak`))
		default:
			w.WriteHeader(404)
		}
	}))
	defer server.Close()

	if got, err := fetchProviderModels(map[string]any{
		"baseUrl": server.URL + "/v1", "apiKey": "secret",
	}); err != nil || len(got) != 1 {
		t.Fatalf("openai: %v %v", got, err)
	}
	t.Setenv("PROVIDER_TEST_KEY", "secret")
	if got, err := fetchProviderModels(map[string]any{
		"baseUrl": server.URL + "/anthropic", "api": "anthropic-messages", "apiKey": "$PROVIDER_TEST_KEY",
	}); err != nil || len(got) != 1 {
		t.Fatalf("anthropic: %v %v", got, err)
	}
	if got, err := fetchProviderModels(map[string]any{
		"baseUrl": server.URL + "/google", "api": "google-generativeai", "apiKey": "secret",
	}); err != nil || len(got) != 1 {
		t.Fatalf("google: %v %v", got, err)
	}
	if got, err := fetchProviderModels(map[string]any{
		"baseUrl": server.URL, "modelsUrl": server.URL + "/custom",
		"headers": map[string]any{"X-Test": "ok"},
	}); err != nil || len(got) != 1 {
		t.Fatalf("custom: %v %v", got, err)
	}
	if _, err := fetchProviderModels(map[string]any{"baseUrl": server.URL, "modelsUrl": server.URL + "/empty"}); err == nil ||
		!strings.Contains(err.Error(), "未返回任何模型") {
		t.Fatalf("empty list: %v", err)
	}
	if _, err := fetchProviderModels(map[string]any{"baseUrl": server.URL, "modelsUrl": server.URL + "/badjson"}); err == nil {
		t.Fatal("bad json accepted")
	}
	if _, err := fetchProviderModels(map[string]any{"baseUrl": server.URL, "modelsUrl": server.URL + "/error?key=leak"}); err == nil ||
		!strings.Contains(err.Error(), "HTTP 500") || strings.Contains(err.Error(), "leak") {
		t.Fatalf("http error: %v", err)
	}
	if _, err := fetchProviderModels(map[string]any{"baseUrl": "http://127.0.0.1:1"}); err == nil {
		t.Fatal("connection error accepted")
	}
}

func TestArchiveErrors(t *testing.T) {
	s := newTestSession(t)
	if _, err := validateArchivedPath("", s.sessionsDir); err == nil {
		t.Fatal("empty archived path accepted")
	}
	if _, err := validateArchivedPath(filepath.Join(s.sessionsDir, "p", "s.jsonl"), s.sessionsDir); err == nil {
		t.Fatal("active session accepted as archived")
	}
	if _, err := validateArchivedPath(filepath.Join(s.cwd, "x.jsonl"), s.sessionsDir); err == nil {
		t.Fatal("outside file accepted as archived")
	}
	if _, err := archivedPath(filepath.Join(s.cwd, "x.jsonl"), s.sessionsDir); err == nil {
		t.Fatal("outside session archived")
	}
	missing := filepath.Join(s.sessionsDir, "p", "missing.jsonl")
	if _, err := archiveSessionFile(missing, s.sessionsDir); err == nil {
		t.Fatal("missing session archived")
	}
	archived := filepath.Join(archiveRoot(s.sessionsDir), "p", "missing.jsonl")
	if err := os.MkdirAll(filepath.Dir(archived), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(archived, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := purgeArchivedFile(archived, s.sessionsDir); err != nil {
		t.Fatal(err)
	}
	if err := purgeArchivedFile(filepath.Join(s.cwd, "x.jsonl"), s.sessionsDir); err == nil {
		t.Fatal("purge outside archive accepted")
	}
	if _, err := insideDir(s.sessionsDir, filepath.Join(s.sessionsDir, "x.txt")); err == nil {
		t.Fatal("non-jsonl accepted")
	}
}

func TestInstallStaleAndWatchMissingStatus(t *testing.T) {
	if installStale(installStatus{Updated: 0}) {
		t.Fatal("zero timestamp considered stale")
	}
	if !installStale(installStatus{Updated: time.Now().Add(-2 * installStatusMaxAge).Unix()}) {
		t.Fatal("old status not stale")
	}
	if installStale(installStatus{Updated: time.Now().Unix()}) {
		t.Fatal("fresh status stale")
	}

	s := newTestSession(t)
	path := filepath.Join(s.cwd, ".pig", "install-status.json")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"state":"running","updated":`+itoa64(time.Now().Unix())+`}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.initInstallGate()
	if s.install.isReady() {
		t.Fatal("running install released the gate")
	}
	if err := os.Remove(path); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) && !s.install.isReady() {
		time.Sleep(20 * time.Millisecond)
	}
	if !s.install.isReady() {
		t.Fatal("removing the status file did not release the gate")
	}
}

func TestSaveProviderBranches(t *testing.T) {
	s, m := memSession(t)
	cases := []map[string]any{
		nil,
		{"id": "BAD ID"},
		{"id": "p", "baseUrl": "ftp://x"},
		{"id": "p", "baseUrl": "http://x", "api": "nope"},
		{"id": "p", "baseUrl": "http://x", "auto": false},
	}
	for _, entry := range cases {
		s.saveProvider("bad", entry)
		if frame := m.frames(t)[0]; frame["type"] != "relay_provider_error" {
			t.Fatalf("bad provider accepted: %#v", entry)
		}
	}

	s.saveProvider("ok", map[string]any{
		"id": "p1", "name": "P", "baseUrl": "http://example.com/", "api": "openai-completions",
		"auto": false, "models": []any{map[string]any{"id": "m1"}},
	})
	frame := m.frames(t)[0]
	if frame["type"] != "relay_provider_saved" {
		t.Fatalf("save: %#v", frame)
	}
	store, _ := readJSONObjectFile(filepath.Join(filepath.Dir(s.settingsPath), "custom-endpoints.json"))
	def := providerMapOf(store)["p1"].(map[string]any)
	if def["baseUrl"] != "http://example.com" || def["api"] != "openai-completions" {
		t.Fatalf("saved provider: %#v", def)
	}

	t.Setenv("PIG_OFFLINE", "1")
	s.saveProvider("auto", map[string]any{
		"id": "p2", "baseUrl": "http://example.com", "auto": true,
	})
	if frame := m.frames(t)[0]; !strings.Contains(frame["warning"].(string), "离线") {
		t.Fatalf("offline save: %#v", frame)
	}
	t.Setenv("PIG_OFFLINE", "")
	s.saveProvider("auto2", map[string]any{
		"id": "p3", "baseUrl": "http://127.0.0.1:1", "auto": true,
	})
	if frame := m.frames(t)[0]; !strings.Contains(frame["warning"].(string), "获取失败") {
		t.Fatalf("fetch failure save: %#v", frame)
	}
}
