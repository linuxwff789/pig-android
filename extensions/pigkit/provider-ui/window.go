package providerui

import (
	"fmt"
	"sort"
	"strings"
)

// apiOptions are the API spellings offered by the window. The custom-endpoints
// file accepts more; those can be typed in config by hand.
var apiOptions = []struct{ Label, Value string }{
	{"OpenAI Chat Completions", "openai-completions"},
	{"OpenAI Responses", "openai-responses"},
	{"Anthropic Messages", "anthropic-messages"},
	{"Google Generative AI", "google-generative-ai"},
}

func knownAPI(value string) bool {
	switch value {
	case "openai-completions", "openai-responses", "anthropic-messages",
		"google-generative-ai", "google-vertex", "mistral-conversations",
		"azure-openai-responses", "openai-codex-responses", "bedrock-converse-stream":
		return true
	}
	return false
}

// writeWindow renders the provider window into the Android UI pack.
func writeWindow() error {
	entries := listEntries()
	stateMu.Lock()
	status, isError := lastStatus, lastError
	stateMu.Unlock()

	children := []any{
		map[string]any{
			"type":  "text",
			"text":  fmt.Sprintf("%d 个自定义供应商 · 保存后当前会话立即生效", len(entries)),
			"style": "muted",
		},
		providerList(entries),
		map[string]any{"type": "text", "text": fmt.Sprintf("models.json 里的 %d 个供应商不在这里修改", modelProviderCount()), "style": "label"},
		map[string]any{"type": "divider"},
		map[string]any{"type": "text", "text": "添加 / 覆盖供应商", "style": "heading"},
		map[string]any{"type": "text", "text": "ID、Base URL 必填；API Key 支持 $ENV 变量，会明文写入 custom-endpoints.json", "style": "label"},
		field("id", "", "ID，例如 my-endpoint", 1),
		field("name", "", "显示名称（可留空）", 1),
		field("baseUrl", "", "Base URL，例如 https://api.example.com/v1", 1),
		field("apiKey", "", "API Key，例如 sk-... 或 $MY_API_KEY", 1),
		map[string]any{
			"type":  "select",
			"id":    "api",
			"value": "openai-completions",
			"options": func() []any {
				out := make([]any, 0, len(apiOptions))
				for _, option := range apiOptions {
					out = append(out, map[string]any{"label": option.Label, "value": option.Value})
				}
				return out
			}(),
		},
		map[string]any{"type": "toggle", "id": "auto", "label": "自动从 {baseUrl}/models 获取模型列表", "value": true},
		field("models", "", "手动模型 ID，逗号分隔；自动获取时留空", 1),
		field("modelsUrl", "", "自定义 models 地址（可选）", 1),
		map[string]any{
			"type":    "row",
			"spacing": 8,
			"children": []any{
				map[string]any{"type": "button", "label": "保存并应用", "action": "ext:providerui.save"},
				map[string]any{"type": "button", "label": "刷新列表", "action": "ext:provider-ui", "buttonStyle": "outline"},
				map[string]any{"type": "button", "label": "关闭", "action": "builtin:close", "buttonStyle": "text"},
			},
		},
	}
	if status != "" {
		style := "body"
		if isError {
			style = "danger"
		}
		children = append(children, map[string]any{"type": "text", "text": status, "style": style})
	}

	window := map[string]any{
		"appUI":       1,
		"id":          "providers",
		"title":       "模型供应商",
		"placement":   "overlay",
		"width":       380,
		"height":      640,
		"dismissible": true,
		"autoOpen":    false,
		"body": map[string]any{
			"type":     "column",
			"spacing":  10,
			"scroll":   true,
			"children": children,
		},
	}
	return writeJSONFile(windowPath(), window)
}

func providerList(entries []entry) map[string]any {
	children := make([]any, 0, len(entries)+1)
	if len(entries) == 0 {
		children = append(children, map[string]any{
			"type":  "text",
			"text":  "还没有自定义供应商，填下面的表单即可添加。",
			"style": "muted",
		})
	}
	for _, item := range entries {
		detail := item.BaseURL
		if detail == "" {
			detail = "（没有 baseUrl）"
		}
		source := "手动模型"
		if item.Auto {
			source = "自动获取"
		}
		children = append(children, map[string]any{
			"type":    "row",
			"spacing": 8,
			"children": []any{
				map[string]any{
					"type":   "column",
					"weight": 1.0,
					"children": []any{
						map[string]any{"type": "text", "text": item.ID + " · " + item.Name, "style": "body"},
						map[string]any{"type": "text", "text": detail, "style": "label"},
						map[string]any{
							"type":  "text",
							"text":  fmt.Sprintf("%s · %d 个模型 · %s", item.API, item.Models, source),
							"style": "label",
						},
					},
				},
				func() any {
					if !item.Auto {
						return map[string]any{
							"type":        "button",
							"label":       "删除",
							"action":      "ext:providerui.remove " + item.ID,
							"buttonStyle": "danger",
						}
					}
					return map[string]any{
						"type":    "row",
						"spacing": 4,
						"children": []any{
							map[string]any{
								"type":        "button",
								"label":       "刷新",
								"action":      "ext:providerui.refresh " + item.ID,
								"buttonStyle": "outline",
							},
							map[string]any{
								"type":        "button",
								"label":       "删除",
								"action":      "ext:providerui.remove " + item.ID,
								"buttonStyle": "danger",
							},
						},
					}
				}(),
			},
		})
	}
	return map[string]any{
		"type":     "list",
		"spacing":  6,
		"children": children,
	}
}

func field(id, value, placeholder string, maxLines int) map[string]any {
	return map[string]any{
		"type":        "textfield",
		"id":          id,
		"value":       value,
		"placeholder": placeholder,
		"maxLines":    maxLines,
	}
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
		"window":    "providers",
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
