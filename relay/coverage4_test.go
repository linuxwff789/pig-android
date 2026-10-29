package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestProviderNormalizeAndMap(t *testing.T) {
	if model, ok := providerNormalizeModel("gpt-4"); !ok || model["id"] != "gpt-4" {
		t.Fatalf("string model: %#v %v", model, ok)
	}
	if _, ok := providerNormalizeModel("  "); ok {
		t.Fatal("blank model accepted")
	}
	if _, ok := providerNormalizeModel(map[string]any{"name": ""}); ok {
		t.Fatal("empty map model accepted")
	}
	model, ok := providerNormalizeModel(map[string]any{
		"id": "models/gemini", "displayName": "Gemini", "contextWindow": float64(1000),
		"max_output_tokens": float64(100), "reasoning": true, "input": []any{"text"},
		"supports_vision": true,
	})
	if !ok || model["id"] != "gemini" || model["name"] != "Gemini" ||
		model["contextWindow"] != int64(1000) || model["maxTokens"] != int64(100) ||
		model["reasoning"] != true || model["input"].([]any)[1] != "image" {
		t.Fatalf("normalized: %#v", model)
	}
	if got := providerExtractModels(map[string]any{"models": []any{1}}); len(got) != 1 {
		t.Fatalf("models key: %v", got)
	}
	if got := providerExtractModels(map[string]any{"result": []any{1}}); len(got) != 1 {
		t.Fatalf("result key: %v", got)
	}
	if got := providerExtractModels(map[string]any{"nope": []any{1}}); got != nil {
		t.Fatalf("unknown key: %v", got)
	}
	if got := providerExtractModels([]any{1, 2}); len(got) != 2 {
		t.Fatalf("top-level list: %v", got)
	}

	legacy := map[string]any{"p1": map[string]any{"id": "p1"}, "defaults": map[string]any{}, "$schema": "x"}
	nested := providerMapOf(legacy)
	if len(nested) != 1 || nested["p1"] == nil {
		t.Fatalf("legacy map: %#v", legacy)
	}
	if _, ok := legacy["p1"]; ok {
		t.Fatal("legacy entry not moved")
	}
	if legacy["defaults"] == nil || legacy["providers"] == nil {
		t.Fatalf("reserved keys: %#v", legacy)
	}
}

func TestJSONFileHelpers(t *testing.T) {
	dir := t.TempDir()
	for _, tc := range []struct {
		name string
		body string
		ok   bool
	}{
		{"missing", "", true},
		{"empty", "  \n", true},
		{"null", "null", true},
		{"bad", "{bad", false},
	} {
		path := filepath.Join(dir, tc.name+".json")
		if tc.body != "" {
			if err := os.WriteFile(path, []byte(tc.body), 0o600); err != nil {
				t.Fatal(err)
			}
		}
		store, err := readJSONObjectFile(path)
		if tc.ok && (err != nil || store == nil) {
			t.Fatalf("%s: %v %v", tc.name, store, err)
		}
		if !tc.ok && err == nil {
			t.Fatalf("%s accepted", tc.name)
		}
	}

	path := filepath.Join(dir, "out.json")
	if err := writeJSONAtomic(path, map[string]any{"a": 1}); err != nil {
		t.Fatal(err)
	}
	body, _ := os.ReadFile(path)
	if !strings.Contains(string(body), `"a": 1`) {
		t.Fatalf("writeJSONAtomic: %s", body)
	}
	if err := writeJSONAtomic(path, map[string]any{"a": 2}); err != nil {
		t.Fatal(err)
	}
	if err := writeSettingsFile(path, map[string]any{"bad": func() {}}); err == nil {
		t.Fatal("unmarshalable settings accepted")
	}

	if os.Geteuid() != 0 {
		locked := filepath.Join(dir, "locked")
		if err := os.Mkdir(locked, 0o500); err != nil {
			t.Fatal(err)
		}
		defer func() { _ = os.Chmod(locked, 0o700) }()
		if err := writeJSONAtomic(filepath.Join(locked, "x.json"), map[string]any{}); err == nil {
			t.Fatal("writeJSONAtomic wrote into a locked dir")
		}
		if err := writeSettingsFile(filepath.Join(locked, "settings.json"), map[string]any{}); err == nil {
			t.Fatal("writeSettingsFile wrote into a locked dir")
		}
	}
}

func TestProviderHTTPClientHostname(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()
	t.Setenv("NO_PROXY", "localhost,127.0.0.1")
	url := strings.Replace(server.URL, "127.0.0.1", "localhost", 1)
	response, err := providerHTTPClient().Get(url)
	if err != nil {
		t.Fatalf("hostname request: %v", err)
	}
	_ = response.Body.Close()
	if response.StatusCode != 200 {
		t.Fatalf("status: %d", response.StatusCode)
	}
}

func TestHandleAgentFrameMoreBranches(t *testing.T) {
	s, m := memSession(t)
	handled := func(typ string, id any) map[string]any {
		s.handleAgentFrame([]byte(`{"type":`+quote(typ)+`,"id":`+quote(id.(string))+`}`), typ, id, "")
		frames := m.frames(t)
		if len(frames) == 0 {
			t.Fatalf("%s: no frame", typ)
		}
		return frames[0]
	}
	if frame := handled("get_session_stats", "st"); frame["type"] != "response" || frame["command"] != "get_session_stats" {
		t.Fatalf("stats: %#v", frame)
	}
	if frame := handled("get_available_thinking_levels", "th"); len(frame["data"].(map[string]any)["levels"].([]any)) != 0 {
		t.Fatalf("thinking levels: %#v", frame)
	}

	parked, parkedOut := testAgent("/parked.jsonl")
	active, _ := testAgent("/active.jsonl")
	s.agentMu.Lock()
	s.agents["/parked.jsonl"] = parked
	s.agents["/active.jsonl"] = active
	s.active, s.activePath = active, "/active.jsonl"
	s.agentMu.Unlock()
	s.handleAgentFrame([]byte(`{"type":"new_session","id":"n1"}`), "new_session", "n1", "")
	if frame := m.frames(t)[0]; frame["success"] != true {
		t.Fatalf("new_session promote: %#v", frame)
	}
	if s.activeAgent() != parked || parkedOut.Len() != 0 {
		t.Fatalf("parked agent not reused: active=%v writes=%q", s.activeAgent(), parkedOut.String())
	}

	path := filepath.Join(s.sessionsDir, "p", "s.jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	s.agentMu.Lock()
	s.active, s.activePath = nil, path
	s.agentMu.Unlock()
	s.pigBin = filepath.Join(s.cwd, "missing-pig")
	s.handleAgentFrame([]byte(`{"type":"get_available_models","id":"am"}`), "get_available_models", "am", path)
	if frame := m.frames(t)[0]; frame["type"] != "relay_error" {
		t.Fatalf("ensureAgent failure: %#v", frame)
	}

	s.agentMu.Lock()
	s.active = nil
	s.agentMu.Unlock()
	s.handleAgentFrame([]byte(`{"type":"extension_ui_response","id":"x"}`), "extension_ui_response", "x", "")
	if frames := m.frames(t); len(frames) != 0 {
		t.Fatalf("extension_ui_response without agent: %#v", frames)
	}
}

func TestApplyRestoreCancelled(t *testing.T) {
	s, m := memSession(t)
	file, err := os.CreateTemp(s.cwd, "staging")
	if err != nil {
		t.Fatal(err)
	}
	path := file.Name()
	s.transfer.applyCancel.Store(true)
	s.applyRestore("c1", &restoreSession{path: path, file: file, written: 1})
	if frames := m.frames(t); len(frames) != 0 {
		t.Fatalf("cancelled restore sent: %#v", frames)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("cancelled staging file survived")
	}
}

func TestSmallHelpers(t *testing.T) {
	if got := readSessionMessages(""); len(got) != 0 {
		t.Fatalf("empty path: %v", got)
	}
	if got := readMessagesFrom(filepath.Join(t.TempDir(), "missing"), 0); got != nil {
		t.Fatalf("missing messages: %v", got)
	}
	s := newTestSession(t)
	if _, err := restoreArchivedFile(filepath.Join(s.cwd, "x.jsonl"), s.sessionsDir); err == nil {
		t.Fatal("restore outside archive accepted")
	}
	if got := resolvePackageEntry("/a/../b", "/agent"); got != "/b" {
		t.Fatalf("absolute package: %q", got)
	}
	if got := resolvePackageEntry("pigkit/x", "/agent"); got != "/agent/pigkit/x" {
		t.Fatalf("relative package: %q", got)
	}
	if got := stringList([]interface{}{"a", float64(1), " b "}); len(got) != 2 || got[1] != "b" {
		t.Fatalf("stringList: %v", got)
	}

	s.extReady = make(chan struct{})
	s.pigBin = filepath.Join(t.TempDir(), "fail")
	if err := os.WriteFile(s.pigBin, []byte("#!/bin/sh\nexit 4\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	s.settingsPath = filepath.Join(t.TempDir(), "agent", "settings.json")
	if err := os.MkdirAll(filepath.Dir(s.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
	s.installEmbeddedPlan(filepath.Dir(s.settingsPath))
	if _, err := os.Stat(filepath.Join(filepath.Dir(s.settingsPath), "extensions", "plan", embeddedPlanHash)); err != nil {
		t.Fatalf("plan sources missing after failed install: %v", err)
	}
}
