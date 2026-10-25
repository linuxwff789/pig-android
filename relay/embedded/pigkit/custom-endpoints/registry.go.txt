// Package customendpoints ports Pi's custom-endpoints extension to Go.
//
// It reads custom provider definitions from JSON, registers each one as a model
// provider, and keeps providers whose model list is "auto" fresh by fetching
// `{baseUrl}/models` and caching the result.
//
// Configuration is merged from, in order:
//
//	$PIG_CUSTOM_ENDPOINTS or $PI_CUSTOM_ENDPOINTS (absolute, or relative to cwd)
//	~/.pig/agent/custom-endpoints.json
//	<cwd>/.pig/custom-endpoints.json
//
// A value anywhere in the config may be written as `$ENV`, `${ENV}` or
// `!command`; `$$` and `$!` are escapes for a literal `$`.
package customendpoints

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

// ttl is the default interval after which an auto endpoint is refetched.
const (
	defaultTTL       = 5 * time.Minute
	fetchTimeout     = 15 * time.Second
	resolveTimeout   = 10 * time.Second
	startupTimeout   = 5 * time.Second
	defaultCtxWindow = 128000
	defaultMaxTokens = 8192
)

// apiTypes are the API spellings the selector offers.
var apiTypes = []string{
	"openai-completions",
	"openai-responses",
	"anthropic-messages",
	"google-generative-ai",
	"google-vertex",
	"mistral-conversations",
	"azure-openai-responses",
	"openai-codex-responses",
	"bedrock-converse-stream",
}

// reservedKeys are top-level keys that are not provider ids.
var reservedKeys = map[string]bool{"defaults": true, "providers": true, "$schema": true}

// ── value resolution ─────────────────────────────────────────────────────────

var envRef = regexp.MustCompile(`\$\{([A-Za-z_][A-Za-z0-9_]*)\}|\$([A-Za-z_][A-Za-z0-9_]*)`)

// resolveValue expands `!command`, `$ENV` and `${ENV}` in raw. `$$` becomes a
// literal `$` and `$!` a literal `!`. A failed command resolves to "".
func resolveValue(raw string) string {
	if raw == "" {
		return ""
	}
	if strings.HasPrefix(raw, "!") {
		return runShell(raw[1:])
	}
	// Protect the escapes with placeholders that the pattern cannot match.
	const dollar = "\x00D\x00"
	const bang = "\x00B\x00"
	value := strings.ReplaceAll(raw, "$$", dollar)
	value = strings.ReplaceAll(value, "$!", bang)
	value = envRef.ReplaceAllStringFunc(value, func(match string) string {
		name := strings.Trim(match, "${}")
		return os.Getenv(name)
	})
	value = strings.ReplaceAll(value, dollar, "$")
	return strings.ReplaceAll(value, bang, "!")
}

// runShell runs command through sh and returns its trimmed stdout.
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

// ── config ──────────────────────────────────────────────────────────────────

// provider is one raw provider definition. Unknown keys are ignored; model
// objects are passed through verbatim so no provider field is lost.
type provider struct {
	Name           string            `json:"name"`
	BaseURL        string            `json:"baseUrl"`
	APIKey         string            `json:"apiKey"`
	API            string            `json:"api"`
	Headers        map[string]string `json:"headers"`
	Defaults       map[string]any    `json:"defaults"`
	Models         json.RawMessage   `json:"models"`
	FetchModels    any               `json:"fetchModels"`
	ModelsURL      string            `json:"modelsUrl"`
	ModelsHeaders  map[string]string `json:"modelsHeaders"`
	RefreshTTLMs   *float64          `json:"refreshTtlMs"`
	RefreshOnStart *bool             `json:"refreshOnStart"`
	OAuth          map[string]any    `json:"oauth"`
}

// auto reports whether the provider's model list is fetched from the endpoint.
func (p provider) auto() bool {
	var marker string
	if err := json.Unmarshal(p.Models, &marker); err == nil && marker == "auto" {
		return true
	}
	if flag, ok := p.FetchModels.(bool); ok && flag {
		return true
	}
	if marker, ok := p.FetchModels.(string); ok && marker == "auto" {
		return true
	}
	return strings.TrimSpace(p.ModelsURL) != ""
}

// ttl is the provider's refresh interval.
func (p provider) ttl() time.Duration {
	if p.RefreshTTLMs != nil && *p.RefreshTTLMs >= 0 {
		return time.Duration(*p.RefreshTTLMs) * time.Millisecond
	}
	return defaultTTL
}

// refreshOnStart reports whether the provider is refreshed with the session.
func (p provider) refreshOnStart() bool {
	return p.RefreshOnStart == nil || *p.RefreshOnStart
}

// config is a merged custom-endpoints file.
type config struct {
	Defaults  map[string]any
	Providers map[string]provider
	Sources   []string
	Errors    []string
}

// location is the config root for agent-level files.
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

// configDirName is the project config directory name.
func configDirName() string {
	if v := os.Getenv("PIG_USE_PI_DIRS"); v == "1" || strings.EqualFold(v, "true") {
		return ".pi"
	}
	return ".pig"
}

// locations lists the config files in load order.
func locations(cwd string) []string {
	var paths []string
	for _, name := range []string{"PIG_CUSTOM_ENDPOINTS", "PI_CUSTOM_ENDPOINTS"} {
		if value := strings.TrimSpace(os.Getenv(name)); value != "" {
			if filepath.IsAbs(value) {
				paths = append(paths, value)
			} else {
				paths = append(paths, filepath.Join(cwd, value))
			}
			break
		}
	}
	paths = append(paths, filepath.Join(agentDir(), "custom-endpoints.json"))
	paths = append(paths, filepath.Join(cwd, configDirName(), "custom-endpoints.json"))
	return paths
}

// readJSONFile decodes path, reporting an absent file as (nil, nil).
func readJSONFile(path string) (any, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil, nil
		}
		return nil, err
	}
	if len(strings.TrimSpace(string(data))) == 0 {
		return nil, nil
	}
	var value any
	if err := json.Unmarshal(data, &value); err != nil {
		return nil, fmt.Errorf("解析 %s 失败: %v", path, err)
	}
	return value, nil
}

// loadConfig merges every readable config file, later files winning.
func loadConfig(cwd string) *config {
	merged := &config{Defaults: map[string]any{}, Providers: map[string]provider{}}
	for _, path := range locations(cwd) {
		data, err := readJSONFile(path)
		if err != nil {
			merged.Errors = append(merged.Errors, err.Error())
			continue
		}
		if data == nil {
			continue
		}
		object, ok := data.(map[string]any)
		if !ok {
			merged.Errors = append(merged.Errors, path+": 顶层必须是对象")
			continue
		}
		merged.Sources = append(merged.Sources, path)
		if defaults, ok := object["defaults"].(map[string]any); ok {
			for key, value := range defaults {
				merged.Defaults[key] = value
			}
		}
		entries := object
		if nested, ok := object["providers"].(map[string]any); ok {
			entries = nested
		}
		for key, value := range entries {
			if reservedKeys[key] {
				continue
			}
			blob, ok := value.(map[string]any)
			if !ok {
				merged.Errors = append(merged.Errors, fmt.Sprintf("%s: provider %q 必须是对象", path, key))
				continue
			}
			encoded, err := json.Marshal(blob)
			if err != nil {
				merged.Errors = append(merged.Errors, fmt.Sprintf("%s: provider %q 无法解析: %v", path, key, err))
				continue
			}
			var def provider
			if err := json.Unmarshal(encoded, &def); err != nil {
				merged.Errors = append(merged.Errors, fmt.Sprintf("%s: provider %q 无法解析: %v", path, key, err))
				continue
			}
			merged.Providers[key] = def
		}
	}
	return merged
}

// ── models ──────────────────────────────────────────────────────────────────

// normalizeModel merges a model definition with the provider and global
// defaults, filling every field Pi expects.
func normalizeModel(raw any, defaults ...map[string]any) (map[string]any, error) {
	var base map[string]any
	switch value := raw.(type) {
	case string:
		base = map[string]any{"id": value}
	case map[string]any:
		base = value
	default:
		return nil, fmt.Errorf("非法 model 定义 %v", raw)
	}
	id, _ := base["id"].(string)
	if id == "" {
		return nil, fmt.Errorf("model 缺少 id")
	}
	merged := map[string]any{
		"id":            id,
		"name":          id,
		"reasoning":     false,
		"input":         []any{"text"},
		"cost":          map[string]any{"input": 0, "output": 0, "cacheRead": 0, "cacheWrite": 0},
		"contextWindow": float64(defaultCtxWindow),
		"maxTokens":     float64(defaultMaxTokens),
	}
	for _, layer := range append([]map[string]any{defaults[0]}, defaults[1:]...) {
		for key, value := range layer {
			if key == "id" || key == "name" {
				continue
			}
			merged[key] = value
		}
	}
	for key, value := range base {
		if value == nil || value == "" {
			continue
		}
		merged[key] = value
	}
	if name, _ := merged["name"].(string); name == "" || name == id {
		merged["name"] = id
	}
	return merged, nil
}

// staticModels decodes a provider's inline model list.
func staticModels(p provider, defaults map[string]any) ([]any, []string) {
	var list []any
	if len(p.Models) == 0 {
		return nil, nil
	}
	if err := json.Unmarshal(p.Models, &list); err != nil {
		return nil, []string{fmt.Sprintf("models 必须是数组或 \"auto\": %v", err)}
	}
	models := make([]any, 0, len(list))
	var errors []string
	for _, raw := range list {
		model, err := normalizeModel(raw, defaults, p.Defaults)
		if err != nil {
			errors = append(errors, err.Error())
			continue
		}
		models = append(models, model)
	}
	return models, errors
}

// extractModels finds the model array in a `{baseUrl}/models` payload.
func extractModels(payload any) []any {
	switch value := payload.(type) {
	case []any:
		return value
	case map[string]any:
		for _, key := range []string{"data", "models", "results"} {
			if list, ok := value[key].([]any); ok {
				return list
			}
		}
		if data, ok := value["data"].(map[string]any); ok {
			if list, ok := data["models"].([]any); ok {
				return list
			}
		}
	}
	return nil
}

// pickString returns a non-empty trimmed string.
func pickString(value any) string {
	if text, ok := value.(string); ok {
		return strings.TrimSpace(text)
	}
	return ""
}

// pickNumber returns a positive integer from a number or numeric string.
func pickNumber(value any) float64 {
	switch v := value.(type) {
	case float64:
		if v > 0 {
			return v
		}
	case string:
		cleaned := strings.NewReplacer(",", "", "_", "").Replace(v)
		if n, err := strconv.ParseFloat(cleaned, 64); err == nil && n > 0 {
			return n
		}
	}
	return 0
}

// liveModel converts one endpoint entry into a model definition.
func liveModel(entry any) (map[string]any, bool) {
	if text, ok := entry.(string); ok {
		return map[string]any{"id": text}, true
	}
	object, ok := entry.(map[string]any)
	if !ok {
		return nil, false
	}
	id := pickString(object["id"])
	if id == "" {
		id = pickString(object["model"])
	}
	if id == "" {
		id = pickString(object["name"])
	}
	if id == "" {
		return nil, false
	}
	id = strings.TrimPrefix(id, "models/")
	model := map[string]any{"id": id, "name": id}
	if name := pickString(object["displayName"]); name != "" {
		model["name"] = strings.TrimPrefix(name, "models/")
	}
	if window := pickNumber(object["context_length"]); window > 0 {
		model["contextWindow"] = window
	} else if window := pickNumber(object["context_window"]); window > 0 {
		model["contextWindow"] = window
	} else if window := pickNumber(object["max_model_len"]); window > 0 {
		model["contextWindow"] = window
	}
	if max := pickNumber(object["max_tokens"]); max > 0 {
		model["maxTokens"] = max
	} else if max := pickNumber(object["max_output_tokens"]); max > 0 {
		model["maxTokens"] = max
	}
	if vision, ok := object["supports_vision"].(bool); ok && vision {
		model["input"] = []any{"text", "image"}
	}
	return model, true
}

// has reports whether any key of headers matches name case-insensitively.
func has(headers map[string]string, name string) bool {
	for key := range headers {
		if strings.EqualFold(key, name) {
			return true
		}
	}
	return false
}

// modelsRequest builds the URL and headers for a model listing.
func modelsRequest(p provider) (string, map[string]string) {
	base := strings.TrimRight(p.BaseURL, "/")
	url := p.ModelsURL
	if url == "" {
		url = base + "/models"
	}
	headers := map[string]string{}
	for key, value := range p.Headers {
		headers[key] = resolveValue(value)
	}
	for key, value := range p.ModelsHeaders {
		headers[key] = resolveValue(value)
	}
	key := resolveValue(p.APIKey)
	if key != "" && !has(headers, "authorization") && !has(headers, "x-api-key") {
		if p.API == "anthropic-messages" {
			headers["x-api-key"] = key
			if !has(headers, "anthropic-version") {
				headers["anthropic-version"] = "2023-06-01"
			}
		} else if strings.HasPrefix(p.API, "google") {
			url += "?key=" + key
		} else {
			headers["Authorization"] = "Bearer " + key
		}
	}
	return url, headers
}

// fetchModels asks the endpoint for its model list.
func fetchModels(p provider) ([]any, error) {
	url, headers := modelsRequest(p)
	client := &http.Client{Timeout: fetchTimeout}
	request, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	for key, value := range headers {
		request.Header.Set(key, value)
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
	for _, entry := range list {
		if model, ok := liveModel(entry); ok {
			models = append(models, model)
		}
	}
	if len(models) == 0 {
		return nil, fmt.Errorf("%s 未返回可识别的模型", url)
	}
	return models, nil
}

// ── cache ───────────────────────────────────────────────────────────────────

// cacheEntry is one provider's cached model list.
type cacheEntry struct {
	FetchedAt int64 `json:"fetchedAt"`
	Models    []any `json:"models"`
}

// cache is the on-disk model cache.
type cache struct {
	mu      sync.Mutex
	path    string
	entries map[string]cacheEntry
}

// newCache loads the cache file.
func newCache() *cache {
	path := filepath.Join(agentDir(), "custom-endpoints.cache.json")
	c := &cache{path: path, entries: map[string]cacheEntry{}}
	data, err := os.ReadFile(path)
	if err != nil {
		return c
	}
	var entries map[string]cacheEntry
	if err := json.Unmarshal(data, &entries); err == nil && entries != nil {
		c.entries = entries
	}
	return c
}

// get returns a provider's cached models.
func (c *cache) get(id string) cacheEntry {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.entries[id]
}

// put stores a provider's models and writes the file.
func (c *cache) put(id string, models []any) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.entries[id] = cacheEntry{FetchedAt: time.Now().UnixMilli(), Models: models}
	c.writeLocked()
}

// remove drops a provider's cached models. Removing an unknown id is a no-op.
func (c *cache) remove(id string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, ok := c.entries[id]; !ok {
		return
	}
	delete(c.entries, id)
	c.writeLocked()
}

// writeLocked persists entries; the caller holds c.mu.
func (c *cache) writeLocked() {
	data, err := json.MarshalIndent(c.entries, "", "  ")
	if err != nil {
		return
	}
	_ = os.MkdirAll(filepath.Dir(c.path), 0o700)
	_ = os.WriteFile(c.path, append(data, '\n'), 0o600)
}

// age renders how long ago a cache entry was fetched.
func age(fetchedAt int64) string {
	if fetchedAt == 0 {
		return "无缓存"
	}
	seconds := int64(time.Since(time.UnixMilli(fetchedAt)).Seconds())
	switch {
	case seconds < 60:
		return fmt.Sprintf("%ds 前", seconds)
	case seconds < 3600:
		return fmt.Sprintf("%dm 前", seconds/60)
	case seconds < 86400:
		return fmt.Sprintf("%dh 前", seconds/3600)
	default:
		return fmt.Sprintf("%dd 前", seconds/86400)
	}
}

// ── registry ────────────────────────────────────────────────────────────────

// endpoint is one registered provider, as the listing shows it.
type endpoint struct {
	ID       string
	Name     string
	BaseURL  string
	API      string
	Models   []string
	Auto     bool
	Fetched  int64
	Override bool
}

// registry owns the registered providers and their model lists.
type registry struct {
	mu       sync.Mutex
	e        *sdk.Extension
	cfg      *config
	cache    *cache
	entries  map[string]provider
	models   map[string][]any
	order    []string
	errors   []string
	sources  []string
	declared map[string]bool
}

// newRegistry builds an empty registry.
func newRegistry(e *sdk.Extension) *registry {
	return &registry{
		e:        e,
		cache:    newCache(),
		entries:  map[string]provider{},
		models:   map[string][]any{},
		declared: map[string]bool{},
	}
}

// sortedIDs returns the provider ids in first-seen order.
func (r *registry) sortedIDs() []string {
	ids := make([]string, 0, len(r.entries))
	for id := range r.entries {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

// load re-reads the config and registers every provider with its cached or
// inline model list.
func (r *registry) load(cwd string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	for id := range r.declared {
		func() {
			defer func() { _ = recover() }()
			r.e.UnregisterProvider(id)
		}()
	}
	r.declared = map[string]bool{}
	r.entries = map[string]provider{}
	r.models = map[string][]any{}
	r.order = nil
	r.errors = nil

	cfg := loadConfig(cwd)
	r.cfg = cfg
	r.sources = cfg.Sources
	r.errors = append(r.errors, cfg.Errors...)

	ids := make([]string, 0, len(cfg.Providers))
	for id := range cfg.Providers {
		ids = append(ids, id)
	}
	sort.Strings(ids)

	for _, id := range ids {
		def := cfg.Providers[id]
		r.entries[id] = def
		r.order = append(r.order, id)

		var models []any
		if def.auto() {
			models = r.cache.get(id).Models
		} else {
			inline, problems := staticModels(def, cfg.Defaults)
			models = inline
			for _, problem := range problems {
				r.errors = append(r.errors, fmt.Sprintf("provider %q: %s", id, problem))
			}
		}
		r.models[id] = models
		if def.auto() && len(models) == 0 {
			// Nothing cached and the fetch has not run yet: registering an empty
			// model list would shadow a working provider from models.json, so the
			// provider is left to that definition until the fetch succeeds.
			r.errors = append(r.errors, fmt.Sprintf("provider %q: 尚未获取模型列表，暂未注册（运行 /endpoints refresh）", id))
			continue
		}
		r.register(id, def, models)
	}
}

// register hands one provider to the host.
func (r *registry) register(id string, def provider, models []any) {
	config := sdk.ProviderConfig{
		"name":    def.Name,
		"baseUrl": resolveValue(def.BaseURL),
		"apiKey":  resolveValue(def.APIKey),
		"api":     def.API,
	}
	if config["name"] == "" {
		config["name"] = id
	}
	if len(def.Headers) > 0 {
		headers := map[string]string{}
		for key, value := range def.Headers {
			headers[key] = resolveValue(value)
		}
		config["headers"] = headers
	}
	if len(models) > 0 {
		config["models"] = models
	}
	if len(def.OAuth) > 0 {
		// An oauth block is passed through only when it needs no callbacks.
		config["oauth"] = def.OAuth
	}
	func() {
		defer func() { _ = recover() }()
		r.e.RegisterProvider(id, config)
		r.declared[id] = true
	}()
}

// dropCache forgets a provider's cached model list, so a removed endpoint does
// not come back from the cache.
func (r *registry) dropCache(id string) {
	r.cache.remove(id)
	r.mu.Lock()
	delete(r.models, id)
	r.mu.Unlock()
}

// refresh refetches the named providers and re-registers them.
func (r *registry) refresh(ids []string) []string {
	var failures []string
	for _, id := range ids {
		r.mu.Lock()
		def, ok := r.entries[id]
		r.mu.Unlock()
		if !ok || !def.auto() {
			continue
		}
		models, err := fetchModels(def)
		if err != nil {
			failures = append(failures, fmt.Sprintf("provider %q: 刷新失败 %v", id, err))
			continue
		}
		defaults := []map[string]any{r.cfg.Defaults, def.Defaults}
		normalized := make([]any, 0, len(models))
		for _, raw := range models {
			if model, err := normalizeModel(raw, defaults...); err == nil {
				normalized = append(normalized, model)
			}
		}
		r.cache.put(id, normalized)
		r.mu.Lock()
		r.models[id] = normalized
		r.mu.Unlock()
		r.register(id, def, normalized)
	}
	return failures
}

// refreshCandidates lists the auto providers to fetch now: those without a
// cache when onlyEmpty is set, otherwise every expired one.
func (r *registry) refreshCandidates(onlyEmpty bool) []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var ids []string
	now := time.Now().UnixMilli()
	for id, def := range r.entries {
		if !def.auto() || !def.refreshOnStart() {
			continue
		}
		entry := r.cache.get(id)
		if onlyEmpty {
			if len(entry.Models) == 0 {
				ids = append(ids, id)
			}
			continue
		}
		if now-entry.FetchedAt > def.ttl().Milliseconds() {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	return ids
}

// autoIDs lists every auto provider.
func (r *registry) autoIDs() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var ids []string
	for id, def := range r.entries {
		if def.auto() {
			ids = append(ids, id)
		}
	}
	sort.Strings(ids)
	return ids
}

// endpoints renders the current registrations.
func (r *registry) errorCount() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.errors)
}

// endpoints renders the current registrations.
func (r *registry) endpoints() []endpoint {
	r.mu.Lock()
	defer r.mu.Unlock()
	list := make([]endpoint, 0, len(r.order))
	for _, id := range r.order {
		def := r.entries[id]
		entry := endpoint{
			ID:      id,
			Name:    def.Name,
			BaseURL: def.BaseURL,
			API:     def.API,
			Auto:    def.auto(),
			Fetched: r.cache.get(id).FetchedAt,
		}
		if entry.Name == "" {
			entry.Name = id
		}
		for _, raw := range r.models[id] {
			if model, ok := raw.(map[string]any); ok {
				if modelID, ok := model["id"].(string); ok {
					entry.Models = append(entry.Models, modelID)
				}
			}
		}
		entry.Override = len(entry.Models) == 0 && !entry.Auto
		list = append(list, entry)
	}
	return list
}
