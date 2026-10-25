// Package providerui adds an in-app window for adding, refreshing and removing
// custom model providers. It writes the same custom-endpoints.json the
// custom-endpoints extension reads, and registers the edited provider in the
// running session immediately.
package providerui

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"strings"
	"sync"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

var (
	ext        *sdk.Extension
	stateMu    sync.Mutex
	lastStatus string
	lastError  bool
)

var idPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,31}$`)

// Extension wires the window commands into the session.
func Extension() *sdk.Extension {
	e := sdk.New("provider-ui")
	ext = e

	// Publish the navigation entry early so the app shows the button even
	// before the window is opened for the first time.
	_ = ensureNav()

	open := func(ctx sdk.Context, _ string) error {
		setStatus("", false)
		if err := ensureNav(); err != nil {
			return fail(ctx, "写入导航配置失败: "+err.Error())
		}
		if err := writeWindow(); err != nil {
			return fail(ctx, "写入窗口失败: "+err.Error())
		}
		if ctx.HasUI() {
			ctx.Notify("已在 App 中打开模型供应商窗口", "info")
		}
		return nil
	}
	e.Command("provider-ui", "打开模型供应商管理窗口（Pig App）", open)
	e.Command("providers", "打开模型供应商管理窗口（Pig App）", open)

	e.Command("providerui.save", "保存供应商表单：/providerui.save {json}", func(ctx sdk.Context, args string) error {
		form, err := parseForm(args)
		if err != nil {
			return fail(ctx, err.Error())
		}
		return save(ctx, form)
	})

	e.Command("providerui.remove", "删除自定义供应商：/providerui.remove <id>", func(ctx sdk.Context, args string) error {
		id := firstArg(args)
		if id == "" {
			return fail(ctx, "缺少供应商 ID")
		}
		return remove(ctx, id)
	})

	e.Command("providerui.refresh", "刷新自动供应商的模型列表：/providerui.refresh <id>", func(ctx sdk.Context, args string) error {
		id := firstArg(args)
		if id == "" {
			return fail(ctx, "缺少供应商 ID")
		}
		return refresh(ctx, id)
	})

	return e
}

func save(ctx sdk.Context, form map[string]any) error {
	id := strings.ToLower(str(form, "id"))
	if !idPattern.MatchString(id) {
		return fail(ctx, "ID 只能是小写字母、数字、点、下划线和短横线（1–32 个字符）")
	}
	baseURL := strings.TrimRight(str(form, "baseUrl"), "/")
	if baseURL == "" {
		return fail(ctx, "Base URL 必填")
	}
	parsed, err := url.Parse(baseURL)
	if err != nil || (parsed.Scheme != "http" && parsed.Scheme != "https") || parsed.Host == "" {
		return fail(ctx, "Base URL 必须是 http(s) 地址，例如 https://api.example.com/v1")
	}
	api := str(form, "api")
	if api == "" {
		api = "openai-completions"
	}
	if !knownAPI(api) {
		return fail(ctx, "不支持的 API 类型: "+api)
	}
	name := str(form, "name")
	if name == "" {
		name = id
	}
	key := str(form, "apiKey")
	auto := boolValue(form, "auto")

	def := map[string]any{
		"name":    name,
		"baseUrl": baseURL,
		"api":     api,
	}
	if key != "" {
		def["apiKey"] = key
	}

	var static []any
	if auto {
		def["models"] = "auto"
		if modelsURL := str(form, "modelsUrl"); modelsURL != "" {
			def["modelsUrl"] = modelsURL
		}
	} else {
		ids := splitList(str(form, "models"))
		if len(ids) == 0 {
			return fail(ctx, "关闭「自动获取」后，至少要填一个模型 ID")
		}
		static = make([]any, 0, len(ids))
		for _, modelID := range ids {
			static = append(static, map[string]any{
				"id":            modelID,
				"name":          modelID,
				"contextWindow": float64(defaultCtxWindow),
				"maxTokens":     float64(defaultMaxTokens),
			})
		}
		def["models"] = static
	}

	store := readObject(endpointsPath())
	providers := providerMap(store)
	_, exists := providers[id]
	providers[id] = def
	if err := writeJSONFile(endpointsPath(), store); err != nil {
		return fail(ctx, "写入 custom-endpoints.json 失败: "+err.Error())
	}
	// Read back so a silent non-write is reported instead of claimed as success.
	check, _ := providerMap(readObject(endpointsPath()))[id].(map[string]any)
	if check == nil || str(check, "baseUrl") != baseURL {
		return fail(ctx, fmt.Sprintf("写入后校验失败：%s 里读不到 %q", endpointsPath(), id))
	}

	action := "已添加"
	if exists {
		action = "已更新"
	}

	var models []any
	message := ""
	switch {
	case !auto:
		models = static
		message = fmt.Sprintf("%s供应商 %q（%d 个手动模型）", action, id, len(models))
	case offline():
		models = cachedModels(id)
		if len(models) > 0 {
			message = fmt.Sprintf("%s供应商 %q（离线，沿用缓存 %d 个模型）", action, id, len(models))
		} else {
			message = fmt.Sprintf("%s供应商 %q（离线，暂未获取模型列表）", action, id)
		}
	default:
		fetched, fetchErr := fetchProviderModels(def)
		if fetchErr != nil {
			models = cachedModels(id)
			if len(models) > 0 {
				message = fmt.Sprintf("%s供应商 %q；刷新失败（%v），沿用缓存 %d 个模型", action, id, fetchErr, len(models))
			} else {
				// Written to disk but useless for the running session: the
				// provider will not show up in /model until it has models.
				message = fmt.Sprintf(
					"%s供应商 %q，已写入 %s；但拿不到模型列表（%v），当前会话还没注册它。"+
						"点「刷新列表」重试，或关掉「自动获取」手动填模型 ID",
					action, id, endpointsPath(), fetchErr)
			}
		} else {
			models = fetched
			_ = writeModelCache(id, models)
			message = fmt.Sprintf("%s供应商 %q，已获取 %d 个模型", action, id, len(models))
		}
	}

	if len(models) > 0 {
		if err := register(id, def, models); err != nil {
			message += "；但注册到当前会话失败: " + err.Error()
			setStatus(message, true)
			_ = writeWindow()
			ctx.Notify(message, "error")
			return nil
		}
		message += "，当前会话已生效"
		return succeed(ctx, message)
	}

	// Nothing to register: say so in red instead of a quiet "已添加", which
	// reads like success while /model shows nothing.
	return fail(ctx, message)
}

func remove(ctx sdk.Context, id string) error {
	store := readObject(endpointsPath())
	providers := providerMap(store)
	if _, ok := providers[id]; !ok {
		hint := ""
		if hasModelsProvider(id) {
			hint = "（它在 models.json 里，本窗口不修改 models.json）"
		}
		return fail(ctx, fmt.Sprintf("没有自定义供应商 %q%s", id, hint))
	}
	delete(providers, id)
	if err := writeJSONFile(endpointsPath(), store); err != nil {
		return fail(ctx, "写入 custom-endpoints.json 失败: "+err.Error())
	}
	if _, still := providerMap(readObject(endpointsPath()))[id]; still {
		return fail(ctx, fmt.Sprintf("删除后校验失败：%s 里仍有 %q", endpointsPath(), id))
	}
	_ = dropModelCache(id)

	message := fmt.Sprintf("已删除供应商 %q", id)
	switch {
	case ctx.ModelProvider() == id:
		message += "；它仍是当前使用的供应商，重连后失效"
	case hasModelsProvider(id):
		message += "；models.json 里仍有同名条目，当前会话保持不变"
	default:
		func() {
			defer func() { _ = recover() }()
			ext.UnregisterProvider(id)
			message += "，当前会话已移除"
		}()
	}
	return succeed(ctx, message)
}

func refresh(ctx sdk.Context, id string) error {
	store := readObject(endpointsPath())
	def, _ := providerMap(store)[id].(map[string]any)
	if def == nil {
		return fail(ctx, fmt.Sprintf("没有自定义供应商 %q", id))
	}
	if !isAuto(def) {
		return fail(ctx, fmt.Sprintf("供应商 %q 使用手动模型列表，无需刷新", id))
	}
	if offline() {
		return fail(ctx, "离线模式下无法刷新模型列表")
	}
	models, err := fetchProviderModels(def)
	if err != nil {
		// A refresh is a network call; keep the last good list rather than
		// dropping the provider out of the session on a hiccup.
		if cached := cachedModels(id); len(cached) > 0 {
			if regErr := register(id, def, cached); regErr == nil {
				return fail(ctx, fmt.Sprintf(
					"刷新 %q 失败（%v），沿用缓存 %d 个模型，当前会话仍然可用",
					id, err, len(cached)))
			}
		}
		return fail(ctx, fmt.Sprintf("刷新 %q 失败: %v", id, err))
	}
	if err := writeModelCache(id, models); err != nil {
		return fail(ctx, "写入模型缓存失败: "+err.Error())
	}
	if err := register(id, def, models); err != nil {
		return fail(ctx, fmt.Sprintf("已刷新 %d 个模型，但注册失败: %v", len(models), err))
	}
	return succeed(ctx, fmt.Sprintf("已刷新供应商 %q：%d 个模型，当前会话已生效", id, len(models)))
}

// register hands one provider to the running session.
func register(id string, def map[string]any, models []any) (err error) {
	if ext == nil {
		return nil
	}
	config := sdk.ProviderConfig{
		"name":    str(def, "name"),
		"baseUrl": resolveValue(str(def, "baseUrl")),
		"api":     str(def, "api"),
	}
	if config["name"] == "" {
		config["name"] = id
	}
	if key := str(def, "apiKey"); key != "" {
		config["apiKey"] = resolveValue(key)
	}
	if len(models) > 0 {
		config["models"] = models
	}
	defer func() {
		if recovered := recover(); recovered != nil {
			err = fmt.Errorf("%v", recovered)
		}
	}()
	ext.RegisterProvider(id, config)
	return nil
}

// cachedModels reads a provider's cached models without a network call.
func cachedModels(id string) []any {
	entry, _ := readObject(cachePath())[id].(map[string]any)
	if entry == nil {
		return nil
	}
	list, _ := entry["models"].([]any)
	return list
}

func succeed(ctx sdk.Context, message string) error {
	setStatus("✓ "+message, false)
	if err := writeWindow(); err != nil {
		ctx.Notify(message+"（窗口刷新失败: "+err.Error()+"）", "warning")
		return nil
	}
	ctx.Notify(message, "info")
	return nil
}

func fail(ctx sdk.Context, message string) error {
	setStatus("✗ "+message, true)
	_ = writeWindow()
	ctx.Notify(message, "error")
	return nil
}

func setStatus(message string, isError bool) {
	stateMu.Lock()
	lastStatus = message
	lastError = isError
	stateMu.Unlock()
}

// parseForm accepts the JSON object the app appends to ext: actions.
func parseForm(args string) (map[string]any, error) {
	start := strings.IndexByte(args, '{')
	if start < 0 {
		return nil, fmt.Errorf("请在 App 窗口中填写表单后点击「保存并应用」")
	}
	var form map[string]any
	if err := json.Unmarshal([]byte(args[start:]), &form); err != nil {
		return nil, fmt.Errorf("表单解析失败: %v", err)
	}
	return form, nil
}

// firstArg returns the id in `/command id {form json}`. The explicit id wins
// over the form payload: button actions append the whole add-provider form,
// whose "id" field is not the row that was clicked.
func firstArg(args string) string {
	args = strings.TrimSpace(args)
	header := args
	if start := strings.IndexByte(args, '{'); start >= 0 {
		header = strings.TrimSpace(args[:start])
		if header == "" {
			if form, err := parseForm(args[start:]); err == nil {
				return strings.ToLower(str(form, "id"))
			}
			return ""
		}
	}
	if index := strings.IndexAny(header, " \t\r\n"); index >= 0 {
		header = header[:index]
	}
	return strings.ToLower(strings.TrimSpace(header))
}

func boolValue(form map[string]any, key string) bool {
	switch value := form[key].(type) {
	case bool:
		return value
	case string:
		switch strings.ToLower(strings.TrimSpace(value)) {
		case "1", "true", "yes", "on":
			return true
		}
	}
	return false
}

func splitList(value string) []string {
	parts := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == '\n' || r == '\r' || r == ' ' || r == '\t'
	})
	seen := map[string]bool{}
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" || seen[part] {
			continue
		}
		seen[part] = true
		out = append(out, part)
	}
	return out
}
