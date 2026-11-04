package main

import (
	"encoding/json"
	"os"
	"path/filepath"
)

// syncProviderAuth mirrors the custom providers' keys into auth.json.
//
// RegisterProvider gives pig everything a normal chat request needs, but
// `set_model` (the app's model switch) and the image preflight resolve the
// provider through pig's credential store instead. A fresh install with only
// custom-endpoints.json therefore answered `No API key found for <provider>`
// the first time the app switched models. Writing the same key here closes
// that gap without asking the user to log in.
func (s *session) syncProviderAuth() {
	configPath, _, _ := s.providerPaths()
	store, err := readJSONObjectFile(configPath)
	if err != nil {
		return
	}
	providers := providerMapOf(store)
	if len(providers) == 0 {
		return
	}
	authPath := filepath.Join(filepath.Dir(s.settingsPath), "auth.json")
	auth, _ := readJSONObjectFile(authPath)
	if auth == nil {
		auth = map[string]any{}
	}
	changed := false
	for id, raw := range providers {
		entry, _ := raw.(map[string]any)
		if entry == nil {
			continue
		}
		key := expandProviderValue(providerString(entry, "apiKey"))
		if key == "" {
			continue
		}
		if existing, _ := auth[id].(map[string]any); existing != nil {
			if current, _ := existing["key"].(string); current == key {
				continue
			}
		}
		auth[id] = map[string]any{"type": "api_key", "key": key}
		changed = true
	}
	if !changed {
		return
	}
	encoded, err := json.MarshalIndent(auth, "", "  ")
	if err != nil {
		return
	}
	if err := writeFileAtomic(authPath, append(encoded, '\n')); err != nil {
		logf("auth.json sync failed: %v", err)
		return
	}
	logf("auth.json: synced %d custom provider credential(s)", len(providers))
}

// removeProviderAuth drops the credential when the provider is deleted. The
// store's own providers (deepseek, opencode-go, ...) are never touched.
func (s *session) removeProviderAuth(id string) {
	if id == "" {
		return
	}
	authPath := filepath.Join(filepath.Dir(s.settingsPath), "auth.json")
	auth, err := readJSONObjectFile(authPath)
	if err != nil || len(auth) == 0 {
		return
	}
	if _, ok := auth[id]; !ok {
		return
	}
	delete(auth, id)
	encoded, err := json.MarshalIndent(auth, "", "  ")
	if err != nil {
		return
	}
	if err := writeFileAtomic(authPath, append(encoded, '\n')); err == nil {
		_ = os.Chmod(authPath, 0o600)
	}
}
