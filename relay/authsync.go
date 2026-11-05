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
//
// ids limits the work to the providers named: starting up passes none and
// syncs everything once, while saving one provider passes only that id. A
// full pass on every save would run every other provider's `!cmd` apiKey
// (expandProviderValue shells out) as a side effect of an unrelated edit.
func (s *session) syncProviderAuth(ids ...string) {
	configPath, _, _ := s.providerPaths()
	store, err := readJSONObjectFile(configPath)
	if err != nil {
		return
	}
	providers := providerMapOf(store)
	if len(providers) == 0 {
		return
	}
	wanted := map[string]bool{}
	for _, id := range ids {
		wanted[id] = true
	}
	authPath := filepath.Join(filepath.Dir(s.settingsPath), "auth.json")
	auth, _ := readJSONObjectFile(authPath)
	if auth == nil {
		auth = map[string]any{}
	}
	changed := 0
	for id, raw := range providers {
		if len(wanted) > 0 && !wanted[id] {
			continue
		}
		entry, _ := raw.(map[string]any)
		if entry == nil {
			continue
		}
		key := expandProviderValue(providerString(entry, "apiKey"))
		existing, _ := auth[id].(map[string]any)
		if key == "" {
			// Only a save prunes (wanted non-empty): on startup a provider
			// without an apiKey may still have a credential the user created
			// with `pig login`, and deleting that would break their login.
			// A plain api_key entry is the mirror this function wrote, so
			// clearing the key in the app removes it instead of leaving a
			// working credential behind.
			if len(wanted) > 0 && existing != nil {
				if kind, _ := existing["type"].(string); kind == "api_key" {
					delete(auth, id)
					changed++
				}
			}
			continue
		}
		if existing != nil {
			// Never rewrite an OAuth (or other) credential as an api_key:
			// pig resolves it through its own login flow.
			if kind, _ := existing["type"].(string); kind != "" && kind != "api_key" {
				continue
			}
			if current, _ := existing["key"].(string); current == key {
				continue
			}
		}
		auth[id] = map[string]any{"type": "api_key", "key": key}
		changed++
	}
	if changed == 0 {
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
	logf("auth.json: synced %d custom provider credential(s)", changed)
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
