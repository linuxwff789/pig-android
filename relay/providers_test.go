package main

import (
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestSaveAutoFetchesModelsWhenCacheIsMissing(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	if err := os.MkdirAll(filepath.Join(home, ".pig", "agent"), 0o700); err != nil {
		t.Fatal(err)
	}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/models" {
			http.NotFound(w, r)
			return
		}
		if r.Header.Get("Authorization") != "Bearer sk-test" {
			w.WriteHeader(http.StatusUnauthorized)
			return
		}
		_, _ = w.Write([]byte(`{"data":[{"id":"a","context_length":1000},{"id":"b"}]}`))
	}))
	defer server.Close()

	s.saveProvider("save-1", map[string]any{
		"id":      "x",
		"name":    "X",
		"baseUrl": server.URL + "/v1",
		"api":     "openai-completions",
		"apiKey":  "sk-test",
		"auto":    true,
	})

	configPath, cachePath, _ := s.providerPaths()
	if _, err := os.Stat(configPath); err != nil {
		t.Fatalf("provider config not written: %v", err)
	}
	cache, err := readJSONObjectFile(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	entry, _ := cache["x"].(map[string]any)
	if entry == nil {
		t.Fatalf("cache entry missing: %v", cache)
	}
	models, _ := entry["models"].([]any)
	if len(models) != 2 {
		t.Fatalf("models = %v, want 2", models)
	}
}

// A provider draft arrives over the socket. Its apiKey must be used literally:
// `!command` is only honoured for a file the user wrote under ~/.pig.
func TestDraftProviderKeyNeverRunsShell(t *testing.T) {
	s, reader := testSessionWithConn(t)
	marker := filepath.Join(t.TempDir(), "pwned")
	var gotAuth string
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		_, _ = w.Write([]byte(`{"data":[{"id":"m"}]}`))
	}))
	defer server.Close()

	go s.sendProviderModels("refresh-1", map[string]any{
		"id":      "x",
		"baseUrl": server.URL + "/v1",
		"api":     "openai-completions",
		"apiKey":  "!touch " + marker,
	})
	frame := readJSONFrame(t, reader)
	if frame["type"] != "relay_provider_models" {
		t.Fatalf("frame = %v", frame)
	}
	if _, err := os.Stat(marker); err == nil {
		t.Fatal("a draft apiKey executed a shell command")
	}
	if want := "Bearer !touch " + marker; gotAuth != want {
		t.Fatalf("Authorization = %q, want %q", gotAuth, want)
	}
}

func TestLiteralProviderValueKeepsDollarExpansion(t *testing.T) {
	t.Setenv("PIG_TEST_KEY", "sk-env")
	got := expandProviderValue(literalProviderValue("$PIG_TEST_KEY"))
	if got != "sk-env" {
		t.Fatalf("env expansion = %q", got)
	}
	if got := expandProviderValue(literalProviderValue("!echo hi")); got != "!echo hi" {
		t.Fatalf("literal bang = %q", got)
	}
	if got := expandProviderValue("!echo hi"); got != "hi" {
		t.Fatalf("stored bang should still expand: %q", got)
	}
}

func TestProviderShellOutputIsCapped(t *testing.T) {
	// printf is a shell builtin, so this does not depend on PATH or on which
	// coreutils the device has.
	// Zero-padded, so TrimSpace keeps every byte and the length is measurable.
	got := providerRunShell(`printf '%070000d' 1`)
	if len(got) != providerShellMaxOutput {
		t.Fatalf("output = %d bytes, want exactly the %d-byte cap", len(got), providerShellMaxOutput)
	}
}
