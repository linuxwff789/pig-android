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
