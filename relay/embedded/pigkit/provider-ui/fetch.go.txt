package providerui

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

const (
	fetchTimeout     = 15 * time.Second
	resolveTimeout   = 10 * time.Second
	defaultCtxWindow = 128000
	defaultMaxTokens = 8192
)

var envRef = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)`)

// offline reports whether network access is disabled.
func offline() bool {
	value := strings.TrimSpace(os.Getenv("PIG_OFFLINE"))
	if value == "" {
		value = strings.TrimSpace(os.Getenv("PI_OFFLINE"))
	}
	switch strings.ToLower(value) {
	case "1", "true", "yes":
		return true
	}
	return false
}

// resolveValue expands `!command`, `$ENV` and `${ENV}`. `$$` and `$!` are
// escapes for a literal `$` and `!`.
func resolveValue(raw string) string {
	if raw == "" {
		return ""
	}
	if strings.HasPrefix(raw, "!") {
		return runShell(raw[1:])
	}
	const dollar = "\x00D\x00"
	const bang = "\x00B\x00"
	value := strings.ReplaceAll(raw, "$$", dollar)
	value = strings.ReplaceAll(value, "$!", bang)
	value = envRef.ReplaceAllStringFunc(value, func(match string) string {
		return os.Getenv(strings.Trim(match, "${}"))
	})
	value = strings.ReplaceAll(value, dollar, "$")
	return strings.ReplaceAll(value, bang, "!")
}

func runShell(command string) string {
	done := make(chan string, 1)
	go func() {
		out, err := exec.Command("sh", "-c", command).Output()
		if err != nil {
			done <- ""
			return
		}
		done <- strings.TrimSpace(string(out))
	}()
	select {
	case value := <-done:
		return value
	case <-time.After(resolveTimeout):
		return ""
	}
}

// fetchProviderModels asks the endpoint for its model list.
func fetchProviderModels(def map[string]any) ([]any, error) {
	base := strings.TrimRight(str(def, "baseUrl"), "/")
	if base == "" {
		return nil, fmt.Errorf("baseUrl 为空")
	}
	url := str(def, "modelsUrl")
	if url == "" {
		url = base + "/models"
	}
	api := str(def, "api")
	key := resolveValue(str(def, "apiKey"))

	headers := map[string]string{}
	if key != "" {
		switch {
		case api == "anthropic-messages":
			headers["x-api-key"] = key
			headers["anthropic-version"] = "2023-06-01"
		case strings.HasPrefix(api, "google"):
			separator := "?"
			if strings.Contains(url, "?") {
				separator = "&"
			}
			url += separator + "key=" + key
		default:
			headers["Authorization"] = "Bearer " + key
		}
	}

	client := &http.Client{Timeout: fetchTimeout}
	request, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	for name, value := range headers {
		request.Header.Set(name, value)
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return nil, fmt.Errorf("%s -> HTTP %d %s", url, response.StatusCode, response.Status)
	}
	var payload any
	if err := json.NewDecoder(response.Body).Decode(&payload); err != nil {
		return nil, err
	}
	list := extractModels(payload)
	if len(list) == 0 {
		return nil, fmt.Errorf("%s 未返回任何模型", url)
	}
	models := make([]any, 0, len(list))
	for _, raw := range list {
		if model, ok := normalizeModel(raw); ok {
			models = append(models, model)
		}
	}
	if len(models) == 0 {
		return nil, fmt.Errorf("%s 未返回可识别的模型", url)
	}
	return models, nil
}

// extractModels finds the model array in the common listing shapes.
func extractModels(payload any) []any {
	switch value := payload.(type) {
	case []any:
		return value
	case map[string]any:
		for _, key := range []string{"data", "models", "items", "result"} {
			if list, ok := value[key].([]any); ok {
				return list
			}
		}
	}
	return nil
}

// normalizeModel turns one listing entry into the SDK's model shape.
func normalizeModel(raw any) (map[string]any, bool) {
	object, ok := raw.(map[string]any)
	if !ok {
		if text, ok := raw.(string); ok && text != "" {
			return map[string]any{"id": text, "name": text}, true
		}
		return nil, false
	}
	id := firstString(object, "id", "name", "model")
	id = strings.TrimPrefix(id, "models/")
	if id == "" {
		return nil, false
	}
	name := firstString(object, "displayName", "name")
	name = strings.TrimPrefix(name, "models/")
	if name == "" || name == id {
		name = id
	}
	model := map[string]any{"id": id, "name": name}
	if window := firstNumber(object, "context_length", "context_window", "max_model_len", "inputTokenLimit"); window > 0 {
		model["contextWindow"] = window
	}
	if max := firstNumber(object, "max_tokens", "max_output_tokens", "outputTokenLimit"); max > 0 {
		model["maxTokens"] = max
	}
	if vision, ok := object["supports_vision"].(bool); ok && vision {
		model["input"] = []any{"text", "image"}
	}
	return model, true
}

func firstString(object map[string]any, keys ...string) string {
	for _, key := range keys {
		if value, ok := object[key].(string); ok && strings.TrimSpace(value) != "" {
			return strings.TrimSpace(value)
		}
	}
	return ""
}

func firstNumber(object map[string]any, keys ...string) float64 {
	for _, key := range keys {
		switch value := object[key].(type) {
		case float64:
			if value > 0 {
				return value
			}
		case json.Number:
			if number, err := value.Float64(); err == nil && number > 0 {
				return number
			}
		}
	}
	return 0
}

// writeModelCache stores a provider's models in custom-endpoints' cache so the
// custom-endpoints extension picks them up on its next load without a fetch.
func writeModelCache(id string, models []any) error {
	store := readObject(cachePath())
	store[id] = map[string]any{
		"fetchedAt": time.Now().UnixMilli(),
		"models":    models,
	}
	return writeJSONFile(cachePath(), store)
}

// dropModelCache removes a provider's cached models.
func dropModelCache(id string) error {
	store := readObject(cachePath())
	if _, ok := store[id]; !ok {
		return nil
	}
	delete(store, id)
	return writeJSONFile(cachePath(), store)
}
