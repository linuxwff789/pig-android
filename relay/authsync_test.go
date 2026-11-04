package main

import (
	"encoding/json"
	"os"
	"path/filepath"
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
