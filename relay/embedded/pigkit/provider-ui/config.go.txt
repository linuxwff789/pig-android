package providerui

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// reservedKeys are top-level keys in a provider store that are not provider ids.
var reservedKeys = map[string]bool{"defaults": true, "providers": true, "$schema": true}

// agentDir is PiG's agent directory (where models.json and settings live).
func agentDir() string {
	if dir := strings.TrimSpace(os.Getenv("PIG_CODING_AGENT_DIR")); dir != "" {
		return dir
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "."
	}
	if root := strings.TrimSpace(os.Getenv("PIG_HOME")); root != "" {
		return filepath.Join(root, "agent")
	}
	return filepath.Join(home, ".pig", "agent")
}

// androidUIDir is the directory the relay serves to the Android app.
func androidUIDir() string {
	if dir := strings.TrimSpace(os.Getenv("PIG_ANDROID_UI_DIR")); dir != "" {
		return dir
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return ".pig/android-ui"
	}
	return filepath.Join(home, ".pig", "android-ui")
}

func endpointsPath() string { return filepath.Join(agentDir(), "custom-endpoints.json") }
func modelsPath() string    { return filepath.Join(agentDir(), "models.json") }
func cachePath() string     { return filepath.Join(agentDir(), "custom-endpoints.cache.json") }
func navPath() string       { return filepath.Join(androidUIDir(), "nav.json") }

// readObject reads a JSON object, returning an empty object when the file is
// missing or unreadable. It never fails the caller: a broken file is treated as
// empty and is only overwritten when the user saves.
func readObject(path string) map[string]any {
	store := map[string]any{}
	data, err := os.ReadFile(path)
	if err != nil {
		return store
	}
	var decoded map[string]any
	if err := json.Unmarshal(data, &decoded); err == nil && decoded != nil {
		store = decoded
	}
	return store
}

// providerMap returns store's provider map, migrating the legacy flat form in
// memory so every read and write uses one shape.
func providerMap(store map[string]any) map[string]any {
	if nested, ok := store["providers"].(map[string]any); ok {
		return nested
	}
	nested := map[string]any{}
	for key, value := range store {
		if reservedKeys[key] {
			continue
		}
		nested[key] = value
		delete(store, key)
	}
	store["providers"] = nested
	return nested
}

// writeJSONFile writes value atomically with owner-only permissions.
func writeJSONFile(path string, value map[string]any) error {
	data, err := json.MarshalIndent(value, "", "  ")
	if err != nil {
		return err
	}
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := tmp.Write(append(data, '\n')); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(name, 0o600); err != nil {
		return err
	}
	return os.Rename(name, path)
}

// entry is one custom provider as shown in the window.
type entry struct {
	ID        string
	Name      string
	BaseURL   string
	API       string
	Auto      bool
	Models    int
	HasKey    bool
	ModelsURL string
}

// listEntries returns the custom endpoints file's providers in id order.
func listEntries() []entry {
	providers := providerMap(readObject(endpointsPath()))
	cache := readObject(cachePath())
	ids := make([]string, 0, len(providers))
	for id := range providers {
		ids = append(ids, id)
	}
	sort.Strings(ids)

	out := make([]entry, 0, len(ids))
	for _, id := range ids {
		def, _ := providers[id].(map[string]any)
		if def == nil {
			continue
		}
		e := entry{
			ID:        id,
			Name:      str(def, "name"),
			BaseURL:   str(def, "baseUrl"),
			API:       str(def, "api"),
			Auto:      isAuto(def),
			HasKey:    str(def, "apiKey") != "",
			ModelsURL: str(def, "modelsUrl"),
		}
		if e.Name == "" {
			e.Name = id
		}
		if e.Auto {
			if cached, ok := cache[id].(map[string]any); ok {
				if list, ok := cached["models"].([]any); ok {
					e.Models = len(list)
				}
			}
		} else if list, ok := def["models"].([]any); ok {
			e.Models = len(list)
		}
		out = append(out, e)
	}
	return out
}

// modelProviderCount counts providers defined in models.json (these are not
// managed by this plugin and are only shown as context).
func modelProviderCount() int {
	return len(providerMap(readObject(modelsPath())))
}

// hasModelsProvider reports whether models.json itself defines id.
func hasModelsProvider(id string) bool {
	_, ok := providerMap(readObject(modelsPath()))[id]
	return ok
}

// isAuto matches custom-endpoints' auto detection.
func isAuto(def map[string]any) bool {
	if marker, ok := def["models"].(string); ok && marker == "auto" {
		return true
	}
	if flag, ok := def["fetchModels"].(bool); ok && flag {
		return true
	}
	if marker, ok := def["fetchModels"].(string); ok && marker == "auto" {
		return true
	}
	return strings.TrimSpace(str(def, "modelsUrl")) != ""
}

func str(def map[string]any, key string) string {
	value, _ := def[key].(string)
	return strings.TrimSpace(value)
}
