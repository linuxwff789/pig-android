package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestSyncProviderAuthMirrorsCustomKeys(t *testing.T) {
	s := newTestSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	config := `{"providers":{
		"eu":{"baseUrl":"https://example.test/v1","apiKey":"sk-eu"},
		"env":{"baseUrl":"https://example.test/v1","apiKey":"$PIG_AUTH_TEST"},
		"nokey":{"baseUrl":"https://example.test/v1"}
	}}`
	if err := os.WriteFile(filepath.Join(agentDir, "custom-endpoints.json"), []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(agentDir, "auth.json"),
		[]byte(`{"deepseek":{"type":"api_key","key":"sk-deep"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PIG_AUTH_TEST", "sk-env")

	s.syncProviderAuth()

	data, err := os.ReadFile(filepath.Join(agentDir, "auth.json"))
	if err != nil {
		t.Fatal(err)
	}
	var auth map[string]map[string]string
	if err := json.Unmarshal(data, &auth); err != nil {
		t.Fatal(err)
	}
	if auth["eu"]["key"] != "sk-eu" || auth["eu"]["type"] != "api_key" {
		t.Fatalf("eu = %v", auth["eu"])
	}
	if auth["env"]["key"] != "sk-env" {
		t.Fatalf("env = %v", auth["env"])
	}
	if _, ok := auth["nokey"]; ok {
		t.Fatal("a provider without a key must not be written")
	}
	if auth["deepseek"]["key"] != "sk-deep" {
		t.Fatalf("existing credential was overwritten: %v", auth["deepseek"])
	}
}

func TestSyncProviderAuthOnlyTouchesNamedProvider(t *testing.T) {
	s := newTestSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	config := `{"providers":{
		"alpha":{"baseUrl":"https://a.test/v1","apiKey":"sk-alpha"},
		"beta":{"baseUrl":"https://b.test/v1","apiKey":"!false"}
	}}`
	if err := os.WriteFile(filepath.Join(agentDir, "custom-endpoints.json"), []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}

	// A save of one provider must not shell out for (or mirror) the others:
	// beta's apiKey is a `!cmd`, which would run here and fail quietly.
	s.syncProviderAuth("alpha")

	data, err := os.ReadFile(filepath.Join(agentDir, "auth.json"))
	if err != nil {
		t.Fatal(err)
	}
	var auth map[string]map[string]string
	if err := json.Unmarshal(data, &auth); err != nil {
		t.Fatal(err)
	}
	if auth["alpha"]["key"] != "sk-alpha" {
		t.Fatalf("alpha = %v", auth["alpha"])
	}
	if _, ok := auth["beta"]; ok {
		t.Fatalf("beta was synced without being named: %v", auth["beta"])
	}
}

func TestSyncProviderAuthLeavesOAuthCredentialAlone(t *testing.T) {
	s := newTestSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	config := `{"providers":{"eu":{"baseUrl":"https://example.test/v1","apiKey":"sk-eu"}}}`
	if err := os.WriteFile(filepath.Join(agentDir, "custom-endpoints.json"), []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}
	// pig login writes its own entry; mirroring a custom key over it would
	// break the login (and type=oauth is not something this relay owns).
	if err := os.WriteFile(filepath.Join(agentDir, "auth.json"),
		[]byte(`{"eu":{"type":"oauth","refresh":"r","access":"a"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.syncProviderAuth("eu")
	data, _ := os.ReadFile(filepath.Join(agentDir, "auth.json"))
	if !strings.Contains(string(data), `"type":"oauth"`) {
		t.Fatalf("oauth credential was rewritten: %s", data)
	}
}

func TestSyncProviderAuthClearsRemovedKeyOnSave(t *testing.T) {
	s := newTestSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	// The provider still exists but no longer names a key.
	config := `{"providers":{"eu":{"baseUrl":"https://example.test/v1"}}}`
	if err := os.WriteFile(filepath.Join(agentDir, "custom-endpoints.json"), []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(agentDir, "auth.json"),
		[]byte(`{"eu":{"type":"api_key","key":"sk-old"},"deepseek":{"type":"api_key","key":"d"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.syncProviderAuth("eu")
	data, _ := os.ReadFile(filepath.Join(agentDir, "auth.json"))
	if strings.Contains(string(data), "sk-old") {
		t.Fatalf("removed key survived a save: %s", data)
	}
	if !strings.Contains(string(data), `"deepseek"`) {
		t.Fatalf("unrelated credential was removed: %s", data)
	}
}

func TestSyncProviderAuthDoesNotPruneOnStartup(t *testing.T) {
	s := newTestSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	config := `{"providers":{"eu":{"baseUrl":"https://example.test/v1"}}}`
	if err := os.WriteFile(filepath.Join(agentDir, "custom-endpoints.json"), []byte(config), 0o600); err != nil {
		t.Fatal(err)
	}
	// A credential created with `pig login` must survive a restart even when
	// the provider has no apiKey in custom-endpoints.json.
	path := filepath.Join(agentDir, "auth.json")
	if err := os.WriteFile(path, []byte(`{"eu":{"type":"api_key","key":"sk-login"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.syncProviderAuth()
	data, _ := os.ReadFile(path)
	if !strings.Contains(string(data), "sk-login") {
		t.Fatalf("startup pruned a login credential: %s", data)
	}
}

func TestRemoveProviderAuthKeepsBuiltins(t *testing.T) {
	s := newTestSession(t)
	agentDir := filepath.Dir(s.settingsPath)
	path := filepath.Join(agentDir, "auth.json")
	if err := os.WriteFile(path, []byte(`{"eu":{"type":"api_key","key":"k"},"deepseek":{"type":"api_key","key":"d"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	s.removeProviderAuth("eu")
	data, _ := os.ReadFile(path)
	var auth map[string]map[string]string
	if err := json.Unmarshal(data, &auth); err != nil {
		t.Fatal(err)
	}
	if _, ok := auth["eu"]; ok {
		t.Fatal("eu credential survived removal")
	}
	if auth["deepseek"]["key"] != "d" {
		t.Fatal("builtin credential was removed")
	}
}
