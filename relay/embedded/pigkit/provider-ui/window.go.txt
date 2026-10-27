package providerui

import (
	"fmt"
	"sort"
	"strings"
)

// knownAPI lists the API spellings the custom-endpoints file accepts.
func knownAPI(value string) bool {
	switch value {
	case "openai-completions", "openai-responses", "anthropic-messages",
		"google-generative-ai", "google-vertex", "mistral-conversations",
		"azure-openai-responses", "openai-codex-responses", "bedrock-converse-stream":
		return true
	}
	return false
}

// ensureNav adds (or refreshes) this plugin's entry in the app navigation
// bar, preserving entries other packages wrote.
func ensureNav() error {
	store := readObject(navPath())
	items := []any{}
	if existing, ok := store["items"].([]any); ok {
		for _, raw := range existing {
			object, ok := raw.(map[string]any)
			if !ok {
				continue
			}
			if name, _ := object["window"].(string); name == "providers" {
				continue
			}
			if id, _ := object["id"].(string); id == "providers" {
				continue
			}
			items = append(items, object)
		}
	}
	items = append(items, map[string]any{
		"id":        "providers",
		"label":     "模型供应商",
		"window":    "native:providers",
		"command":   "provider-ui",
		"icon":      "build",
		"placement": "settings",
	})
	store["items"] = items
	sort.SliceStable(items, func(i, j int) bool {
		left, _ := items[i].(map[string]any)
		right, _ := items[j].(map[string]any)
		return strings.Compare(fmt.Sprint(left["label"]), fmt.Sprint(right["label"])) < 0
	})
	return writeJSONFile(navPath(), store)
}
