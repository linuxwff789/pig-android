package customendpoints

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

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

// globalPath is the writable config the add/remove commands edit.
func globalPath() string { return filepath.Join(agentDir(), "custom-endpoints.json") }

// modelsPath is PiG's provider store, which /model reads at startup.
func modelsPath() string { return filepath.Join(agentDir(), "models.json") }

// settingsPath is PiG's settings file, which holds defaultProvider.
func settingsPath() string { return filepath.Join(agentDir(), "settings.json") }

// readGlobal returns the global file as a generic object, preserving keys this
// port does not understand. A flat file (provider ids at the top level) is
// migrated in memory to the nested "providers" form, so every command edits and
// writes one shape.
func readGlobal() map[string]any {
	store := map[string]any{}
	data, err := os.ReadFile(globalPath())
	if err == nil {
		var decoded map[string]any
		if err := json.Unmarshal(data, &decoded); err == nil && decoded != nil {
			store = decoded
		}
	}
	providerMap(store)
	return store
}

// providerMap returns store's provider map, creating it. A flat file (provider
// ids at the top level) is migrated in memory to the nested "providers" form,
// so every command edits and writes one shape. The map it returns is attached
// to store, never store itself, so writing the file can not produce a
// self-referencing object.
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

// globalProviders returns the global file's provider map, creating it.
func globalProviders(store map[string]any) map[string]any { return providerMap(store) }

// writeJSONFile writes value to path atomically with owner-only permissions.
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

// writeGlobal saves the global file.
func writeGlobal(store map[string]any) error { return writeJSONFile(globalPath(), store) }

// readJSONObject reads path as an object, returning an empty one when the file
// is absent or unreadable.
func readJSONObject(path string) map[string]any {
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

// sortedKeys lists a provider map's ids in a stable order.
func sortedKeys(providers map[string]any) []string {
	ids := make([]string, 0, len(providers))
	for id := range providers {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	return ids
}

// providerModelID reads the first model id of a provider definition.
func providerModelID(def any) string {
	blob, ok := def.(map[string]any)
	if !ok {
		return ""
	}
	list, _ := blob["models"].([]any)
	for _, raw := range list {
		switch model := raw.(type) {
		case string:
			return model
		case map[string]any:
			if id, ok := model["id"].(string); ok && id != "" {
				return id
			}
		}
	}
	return ""
}

// providerModelFromSources returns the first model id declared for id in the
// writable provider maps, falling back to the models the runtime registered
// (which is where "auto" endpoints keep their fetched list).
func providerModelFromSources(r *registry, id string, maps ...map[string]any) string {
	for _, providers := range maps {
		if modelID := providerModelID(providers[id]); modelID != "" {
			return modelID
		}
	}
	for _, ep := range r.endpoints() {
		if ep.ID == id && len(ep.Models) > 0 {
			return ep.Models[0]
		}
	}
	return ""
}

// parseList splits a comma- or space-separated list.
func parseList(value string) []string {
	fields := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\t' || r == '\n'
	})
	out := make([]string, 0, len(fields))
	for _, field := range fields {
		if field = strings.TrimSpace(field); field != "" {
			out = append(out, field)
		}
	}
	return out
}

// summary renders the endpoint listing.
func summary(r *registry) []string {
	r.mu.Lock()
	sources := append([]string{}, r.sources...)
	errors := append([]string{}, r.errors...)
	r.mu.Unlock()

	var lines []string
	if len(sources) == 0 {
		lines = append(lines, "未找到配置文件。可运行 /endpoint-add 交互式创建。")
	} else {
		lines = append(lines, "配置文件: "+strings.Join(sources, ", "))
	}
	lines = append(lines, "")
	list := r.endpoints()
	if len(list) == 0 {
		lines = append(lines, "没有注册任何自定义端点。")
	}
	for _, ep := range list {
		kind := "override"
		switch {
		case ep.Auto:
			kind = "auto"
		case len(ep.Models) > 0:
			kind = fmt.Sprintf("%d models", len(ep.Models))
		}
		lines = append(lines, fmt.Sprintf("• %s  (%s) [%s]", ep.ID, ep.Name, kind))
		if ep.BaseURL != "" {
			lines = append(lines, "    baseUrl: "+ep.BaseURL)
		}
		if ep.API != "" {
			lines = append(lines, "    api: "+ep.API)
		}
		if ep.Auto {
			lines = append(lines, fmt.Sprintf("    缓存: %s · %d 个模型", age(ep.Fetched), len(ep.Models)))
		}
		switch {
		case len(ep.Models) == 0:
		case !ep.Auto || len(ep.Models) <= 8:
			lines = append(lines, "    models: "+strings.Join(ep.Models, ", "))
		default:
			lines = append(lines, "    models: "+strings.Join(ep.Models[:8], ", ")+" …")
		}
	}
	if len(errors) > 0 {
		lines = append(lines, "", "错误:")
		for _, err := range errors {
			lines = append(lines, "  ! "+err)
		}
	}
	return lines
}

// notifyLines shows a multi-line report. The host's notify is one line, so a
// listing goes through the selector, which renders a list well.
func notifyLines(ctx sdk.Context, lines []string, level string) {
	if ctx.HasUI() && len(lines) > 4 {
		_, _, _ = ctx.Select("Custom Endpoints", lines)
		return
	}
	ctx.Notify(strings.Join(lines, "\n"), level)
}

// Extension builds the custom-endpoints extension.
func Extension() *sdk.Extension {
	e := sdk.New("custom-endpoints")
	r := newRegistry(e)

	if cwd, err := os.Getwd(); err == nil {
		func() {
			defer func() { _ = recover() }()
			r.load(cwd)
		}()
	}

	e.OnSessionStart(func(ctx sdk.Context, data map[string]any) (any, error) {
		// The registry was loaded before the session's cwd was known, so reload
		// against it to pick up a project-level config file.
		r.load(ctx.Cwd())

		if r.errorCount() > 0 {
			ctx.Notify(fmt.Sprintf("custom-endpoints: %d 个错误，运行 /endpoints 查看", r.errorCount()), "warning")
		}
		if offline() {
			return nil, nil
		}

		// A first run has no cached model list; fetch it now so /model and
		// --list-models see the endpoint's models straight away.
		if ids := r.refreshCandidates(true); len(ids) > 0 {
			r.refresh(ids)
		}
		// Everything else refreshes in the background so startup never waits.
		if ids := r.refreshCandidates(false); len(ids) > 0 {
			go func() {
				defer func() { _ = recover() }()
				r.refresh(ids)
			}()
		}
		return nil, nil
	})

	e.Command("endpoints", "自定义端点：/endpoints [refresh|reload]", func(ctx sdk.Context, args string) error {
		switch strings.ToLower(strings.TrimSpace(args)) {
		case "reload", "刷新配置":
			r.load(ctx.Cwd())
			if count := r.errorCount(); count > 0 {
				ctx.Notify(fmt.Sprintf("配置已重载，但有 %d 个错误", count), "warning")
			} else {
				ctx.Notify(fmt.Sprintf("配置已重载：%d 个端点", len(r.endpoints())), "info")
			}
			return nil
		case "refresh", "刷新":
			if offline() {
				ctx.Notify("离线模式下无法刷新模型列表", "warning")
				return nil
			}
			ids := r.autoIDs()
			if len(ids) == 0 {
				ctx.Notify("没有配置自动获取模型的端点", "info")
				return nil
			}
			failures := r.refresh(ids)
			if len(failures) > 0 {
				ctx.Notify(fmt.Sprintf("刷新完成，%d 个失败：%s", len(failures), strings.Join(failures, "; ")), "warning")
			} else {
				ctx.Notify(fmt.Sprintf("已实时刷新 %d 个端点的模型列表", len(ids)), "info")
			}
			return nil
		}
		notifyLines(ctx, summary(r), "info")
		return nil
	})

	e.Command("endpoint-add", "添加自定义端点：/endpoint-add [id baseUrl [apiKey] [api]]", func(ctx sdk.Context, args string) error {
		fields := parseList(args)
		id, baseURL, apiKey, api := "", "", "", ""
		if len(fields) > 0 {
			id = fields[0]
		}
		if len(fields) > 1 {
			baseURL = fields[1]
		}
		if len(fields) > 2 {
			apiKey = fields[2]
		}
		if len(fields) > 3 {
			api = fields[3]
		}
		if ctx.HasUI() && id == "" {
			id = strings.TrimSpace(ask(ctx, "端点 ID（用于 /model 选择）", "my-endpoint"))
		}
		if id == "" {
			ctx.Notify("已取消：端点 ID 不能为空。用法: /endpoint-add <id> <baseUrl> [apiKey] [api]", "info")
			return nil
		}
		for _, ep := range r.endpoints() {
			if ep.ID != id {
				continue
			}
			confirmed, err := ctx.Confirm("已存在", fmt.Sprintf("%q 已注册，是否覆盖配置文件中的定义？", id))
			ok, _ := confirmed.(bool)
			if err != nil || !ok {
				return nil
			}
			break
		}

		name := id
		if ctx.HasUI() {
			if value := strings.TrimSpace(ask(ctx, "显示名称", id)); value != "" {
				name = value
			}
		}
		if ctx.HasUI() && baseURL == "" {
			baseURL = strings.TrimSpace(ask(ctx, "Base URL", "https://api.example.com/v1"))
		}
		if baseURL == "" {
			ctx.Notify("已取消：baseUrl 必填。用法: /endpoint-add <id> <baseUrl> [apiKey] [api]", "warning")
			return nil
		}
		if ctx.HasUI() && apiKey == "" {
			apiKey = strings.TrimSpace(ask(ctx, "API Key（支持 $ENV_VAR 引用环境变量）", "$MY_API_KEY"))
		}

		if api == "" {
			if !ctx.HasUI() {
				api = apiTypes[0]
			} else {
				chosenValue, ok, err := ctx.Select("选择 API 类型", apiTypes)
				if err != nil || !ok {
					ctx.Notify("已取消添加端点", "info")
					return nil
				}
				api, _ = chosenValue.(string)
			}
		}

		mode := "auto（实时从端点获取）"
		if ctx.HasUI() {
			chosenValue, ok, err := ctx.Select("模型列表来源", []string{"auto（实时从端点获取）", "manual（手动输入模型 ID）"})
			if err != nil || !ok {
				ctx.Notify("已取消添加端点", "info")
				return nil
			}
			mode, _ = chosenValue.(string)
		}

		def := map[string]any{"name": name, "baseUrl": baseURL, "api": api}
		if apiKey != "" {
			def["apiKey"] = apiKey
		}
		if strings.HasPrefix(mode, "auto") {
			def["models"] = "auto"
			if ctx.HasUI() {
				if custom := strings.TrimSpace(ask(ctx, "自定义 models 地址（留空使用 {baseUrl}/models）", "")); custom != "" {
					def["modelsUrl"] = custom
				}
			}
		} else {
			ids := []string{}
			if ctx.HasUI() {
				ids = parseList(ask(ctx, "模型 ID（逗号分隔）", "gpt-4o, gpt-4o-mini"))
			}
			if len(ids) == 0 {
				ctx.Notify("已取消：至少需要一个模型 ID", "warning")
				return nil
			}
			window := defaultCtxWindow
			maxTokens := defaultMaxTokens
			reasoning, vision := false, false
			if ctx.HasUI() {
				window = parseNumber(ask(ctx, "上下文窗口 (tokens)", "128000"), defaultCtxWindow)
				maxTokens = parseNumber(ask(ctx, "最大输出 (tokens)", "8192"), defaultMaxTokens)
				reasoningValue, _ := ctx.Confirm("能力", "该模型支持推理/思考 (reasoning)？")
				reasoning, _ = reasoningValue.(bool)
				visionValue, _ := ctx.Confirm("能力", "该模型支持图片输入？")
				vision, _ = visionValue.(bool)
			}
			input := []any{"text"}
			if vision {
				input = []any{"text", "image"}
			}
			models := make([]any, 0, len(ids))
			for _, modelID := range ids {
				models = append(models, map[string]any{
					"id":            modelID,
					"name":          modelID,
					"reasoning":     reasoning,
					"input":         input,
					"contextWindow": float64(window),
					"maxTokens":     float64(maxTokens),
				})
			}
			def["models"] = models
		}

		store := readGlobal()
		providers := globalProviders(store)
		providers[id] = def
		if err := writeGlobal(store); err != nil {
			ctx.Notify("写入配置失败: "+err.Error(), "error")
			return nil
		}
		// Read the file back: a write that silently did not land was the old
		// symptom, so a failed round-trip is reported instead of claimed success.
		if _, ok := globalProviders(readGlobal())[id]; !ok {
			ctx.Notify(fmt.Sprintf("写入后校验失败：%s 中读不到 %q", globalPath(), id), "error")
			return nil
		}

		r.load(ctx.Cwd())
		if def["models"] == "auto" && !offline() {
			r.refresh([]string{id})
		}
		ctx.Notify(fmt.Sprintf("已添加端点 %q 并写入 %s（已热更新）", id, globalPath()), "info")
		return nil
	})

	e.Command("endpoint-remove", "删除端点：/endpoint-remove [id]，不带参数时列表选择", func(ctx sdk.Context, args string) error {
		globalStore := readGlobal()
		globalEntries := globalProviders(globalStore)

		modelsStore := readJSONObject(modelsPath())
		modelsEntries := providerMap(modelsStore)

		type target struct{ id, source string }
		var targets []target
		index := map[string]int{}
		add := func(id, source string) {
			if id == "" {
				return
			}
			if _, seen := index[id]; seen {
				return
			}
			index[id] = len(targets)
			targets = append(targets, target{id: id, source: source})
		}
		for _, id := range sortedKeys(globalEntries) {
			add(id, "custom-endpoints.json")
		}
		for _, id := range sortedKeys(modelsEntries) {
			add(id, "models.json")
		}
		// Endpoints only the merged registry knows come from a project file or
		// PIG_CUSTOM_ENDPOINTS; listing them explains why removal is refused.
		for _, ep := range r.endpoints() {
			add(ep.ID, "仅运行时（项目配置/环境变量）")
		}
		if len(targets) == 0 {
			ctx.Notify("没有可删除的端点：custom-endpoints.json 和 models.json 都是空的", "info")
			return nil
		}

		id := strings.TrimSpace(args)
		source := ""
		if id == "" {
			if !ctx.HasUI() {
				ids := make([]string, len(targets))
				for i, t := range targets {
					ids[i] = t.id + "(" + t.source + ")"
				}
				ctx.Notify("用法: /endpoint-remove <id>；可选: "+strings.Join(ids, ", "), "warning")
				return nil
			}
			labels := make([]string, len(targets))
			for i, t := range targets {
				labels[i] = fmt.Sprintf("%s  (%s)", t.id, t.source)
			}
			chosenValue, ok, err := ctx.Select("选择要删除的端点", labels)
			if err != nil || !ok {
				return nil
			}
			chosen, _ := chosenValue.(string)
			for i, label := range labels {
				if label == chosen {
					id, source = targets[i].id, targets[i].source
					break
				}
			}
		} else if i, ok := index[id]; ok {
			source = targets[i].source
		}
		if source == "" {
			ctx.Notify(fmt.Sprintf("未找到端点 %q，运行 /endpoints 查看已注册端点", id), "warning")
			return nil
		}

		switch source {
		case "custom-endpoints.json":
			if ctx.HasUI() {
				confirmed, err := ctx.Confirm("删除端点", fmt.Sprintf("从 custom-endpoints.json 删除 %q？", id))
				ok, _ := confirmed.(bool)
				if err != nil || !ok {
					return nil
				}
			}
			delete(globalEntries, id)
			if err := writeGlobal(globalStore); err != nil {
				ctx.Notify("写入配置失败: "+err.Error(), "error")
				return nil
			}
			r.dropCache(id)
			r.load(ctx.Cwd())
			ctx.Notify(fmt.Sprintf("已删除端点 %q（已热更新）", id), "info")
			return nil

		case "models.json":
			if ctx.HasUI() {
				confirmed, err := ctx.Confirm("删除 provider", fmt.Sprintf("从 models.json 删除 provider %q？", id))
				ok, _ := confirmed.(bool)
				if err != nil || !ok {
					return nil
				}
			}
			settingsStore := readJSONObject(settingsPath())
			if current, _ := settingsStore["defaultProvider"].(string); current == id {
				// A replacement may live in models.json, in custom-endpoints.json
				// or only in the loaded registry (project file / env), so count
				// every source before declaring this the last provider.
				remaining := make([]string, 0, len(modelsEntries)+len(globalEntries))
				seen := map[string]bool{id: true}
				addCandidate := func(other string) {
					if other == "" || seen[other] {
						return
					}
					seen[other] = true
					remaining = append(remaining, other)
				}
				for _, other := range sortedKeys(modelsEntries) {
					addCandidate(other)
				}
				for _, other := range sortedKeys(globalEntries) {
					addCandidate(other)
				}
				for _, ep := range r.endpoints() {
					addCandidate(ep.ID)
				}
				if len(remaining) == 0 {
					ctx.Notify(fmt.Sprintf("不能删除 %q：这是最后一个 provider，删掉后没有可用的默认 provider", id), "warning")
					return nil
				}
				next := remaining[0]
				if ctx.HasUI() {
					if chosenValue, ok, err := ctx.Select("该 provider 是当前默认，选择新的默认 provider", remaining); err == nil && ok {
						next, _ = chosenValue.(string)
					}
				}
				settingsStore["defaultProvider"] = next
				if modelID := providerModelFromSources(r, next, modelsEntries, globalEntries); modelID != "" {
					settingsStore["defaultModel"] = modelID
				} else {
					ctx.Notify(fmt.Sprintf("默认 provider 已切换为 %q，但找不到它的模型 id，请运行 /model 选一个模型", next), "warning")
				}
				if err := writeJSONFile(settingsPath(), settingsStore); err != nil {
					ctx.Notify("更新默认模型失败: "+err.Error(), "error")
					return nil
				}
				ctx.Notify(fmt.Sprintf("默认 provider 已切换为 %q", next), "info")
			}
			delete(modelsEntries, id)
			if err := writeJSONFile(modelsPath(), modelsStore); err != nil {
				ctx.Notify("写入 models.json 失败: "+err.Error(), "error")
				return nil
			}
			r.dropCache(id)
			r.load(ctx.Cwd())
			ctx.Notify(fmt.Sprintf("已从 models.json 删除 provider %q（新会话生效）", id), "info")
			return nil

		default:
			ctx.Notify(fmt.Sprintf("%q 的定义不在可写文件里（%s），请编辑对应文件后运行 /endpoints reload", id, source), "warning")
			return nil
		}
	})

	return e
}

// ask reads one line of input, returning "" when cancelled.
func ask(ctx sdk.Context, title, placeholder string) string {
	value, ok, err := ctx.Input(title, placeholder)
	if err != nil || !ok {
		return ""
	}
	text, _ := value.(string)
	return text
}

// parseNumber reads a count, falling back when the text is not a positive
// integer.
func parseNumber(value string, fallback int) int {
	cleaned := strings.NewReplacer(",", "", "_", "").Replace(strings.TrimSpace(value))
	if cleaned == "" {
		return fallback
	}
	n := 0
	for _, r := range cleaned {
		if r < '0' || r > '9' {
			return fallback
		}
		n = n*10 + int(r-'0')
	}
	if n <= 0 {
		return fallback
	}
	return n
}
