// Package codegraph ports Pi's codegraph extension to Go.
//
// It exposes the codegraph CLI as three model-callable tools so the agent can
// use the symbol graph (who calls a function, what it calls) instead of text
// matching. Prerequisites: the codegraph CLI on PATH and an index built with
// `codegraph init`.
package codegraph

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const (
	// timeoutMs bounds one CLI call: index queries are local SQLite, normally
	// well under a second.
	timeoutMs = 60_000
	// syncTimeoutMs bounds the incremental sync that runs before each lookup.
	// A home-level index (~5k files) needs ~7s; a large repo can take longer.
	syncTimeoutMs = 180_000
	// maxOutputChars caps what reaches the model; explore can emit hundreds of
	// kilobytes on a large project.
	maxOutputChars = 60_000
)

var (
	reMissing   = regexp.MustCompile(`ENOENT|executable file not found`)
	reNoIndex   = regexp.MustCompile(`(?i)not initialized|no index|\.codegraph`)
	notFoundMsg = "codegraph 未安装或不在 PATH。"
)

// run invokes the CLI. query/node/explore take the project path through -p,
// while status accepts only a positional path and rejects -p. The path is
// always explicit because the extension process's cwd need not be the
// session's working directory.
func run(ctx sdk.Context, args []string, positionalPath bool) (string, bool) {
	argv := args
	if positionalPath {
		argv = append(append([]string{}, args...), ctx.Cwd())
	} else {
		argv = append(append([]string{}, args...), "-p", ctx.Cwd())
	}
	res, err := ctx.ExecWithOptions("codegraph", argv, sdk.ExecOptions{
		Timeout: timeoutMs,
		Cwd:     ctx.Cwd(),
	})
	if err != nil {
		return fmt.Sprintf("codegraph 失败: %v", err), false
	}
	detail := strings.TrimSpace(res.Stderr)
	if res.ExitCode != 0 || res.Killed {
		if detail == "" {
			detail = fmt.Sprintf("exit %d", res.ExitCode)
		}
		if reMissing.MatchString(detail) {
			return notFoundMsg, false
		}
		if reNoIndex.MatchString(detail) {
			return "当前目录尚未建立索引。先运行: codegraph init " + ctx.Cwd(), false
		}
		if len(detail) > 2000 {
			detail = detail[:2000]
		}
		return "codegraph 失败: " + detail, false
	}
	return res.Stdout, true
}

func clamp(text string) string {
	if len(text) <= maxOutputChars {
		return text
	}
	return fmt.Sprintf("%s\n\n[输出已截断，丢弃 %d 字符。缩小查询范围或降低 --max-files/--limit 后重试。]",
		text[:maxOutputChars], len(text)-maxOutputChars)
}

// The index does NOT update itself here. CodeGraph's live watcher only runs
// inside `codegraph serve --mcp`; this extension shells out to the CLI, whose
// query/node/explore commands read the DB as-is. Without the sync below,
// symbols added since the last build stay invisible (the CLI only reports them
// as pendingChanges).
var (
	syncMu     sync.Mutex
	lastSyncAt time.Time
)

// syncThrottleMs resolves the throttle window. Precedence: env
// CODEGRAPH_SYNC_THROTTLE_MS, then $HOME/.pig/agent/codegraph.json
// ({"syncThrottleMs": 30000}), then 0 — sync on every lookup. The config file
// exists because the Android app launches pig with a fixed environment, so an
// env var is not reachable from there.
func syncThrottleMs() int {
	if v := strings.TrimSpace(os.Getenv("CODEGRAPH_SYNC_THROTTLE_MS")); v != "" {
		if ms, err := strconv.Atoi(v); err == nil && ms >= 0 {
			return ms
		}
	}
	path := filepath.Join(os.Getenv("HOME"), ".pig", "agent", "codegraph.json")
	data, err := os.ReadFile(path)
	if err != nil {
		return 0
	}
	var cfg struct {
		SyncThrottleMs int `json:"syncThrottleMs"`
	}
	if json.Unmarshal(data, &cfg) != nil || cfg.SyncThrottleMs < 0 {
		return 0
	}
	return cfg.SyncThrottleMs
}

// syncIndex refreshes the index in place. Failures are swallowed on purpose:
// the lookup that follows reports the real problem (CLI missing, no index).
func syncIndex(ctx sdk.Context) {
	syncMu.Lock()
	defer syncMu.Unlock()
	if throttle := syncThrottleMs(); throttle > 0 && !lastSyncAt.IsZero() &&
		time.Since(lastSyncAt) < time.Duration(throttle)*time.Millisecond {
		return
	}
	_, _ = ctx.ExecWithOptions("codegraph", []string{"sync", "-q", ctx.Cwd()}, sdk.ExecOptions{
		Timeout: syncTimeoutMs,
		Cwd:     ctx.Cwd(),
	})
	lastSyncAt = time.Now()
}

func textResult(text string, isError bool) sdk.ToolResult {
	return sdk.ToolResult{Content: text, IsError: isError}
}

// param reads a tool argument.
func param(params map[string]any, key string) (any, bool) {
	v, ok := params[key]
	return v, ok
}

func strParam(params map[string]any, key string) string {
	v, _ := params[key].(string)
	return v
}

func numParam(params map[string]any, key string, fallback int) int {
	switch v := params[key].(type) {
	case float64:
		return int(v)
	case int:
		return v
	case json.Number:
		if f, err := v.Int64(); err == nil {
			return int(f)
		}
	}
	return fallback
}

func boolParam(params map[string]any, key string) bool {
	v, _ := params[key].(bool)
	return v
}

// nodeShape is the subset of a query result the port formats.
type nodeShape struct {
	Kind          string
	Name          string
	QualifiedName string
	FilePath      string
	File          string
	Path          string
	Line          any
	StartLine     any
	Signature     string
}

func location(n nodeShape) string {
	for _, candidate := range []string{n.FilePath, n.File, n.Path} {
		if candidate != "" {
			return candidate
		}
	}
	return "?"
}

func lineOf(n nodeShape) any {
	if n.StartLine != nil {
		return n.StartLine
	}
	if n.Line != nil {
		return n.Line
	}
	return "?"
}

func nameOf(n nodeShape) string {
	if n.QualifiedName != "" {
		return n.QualifiedName
	}
	if n.Name != "" {
		return n.Name
	}
	return "?"
}

func kindOf(n nodeShape) string {
	if n.Kind == "" {
		return "symbol"
	}
	return n.Kind
}

// formatQuery renders the CLI's -j output. The JSON shape is not contractual,
// so a parse failure falls back to the raw text rather than failing the tool.
func formatQuery(rawText, search string) string {
	var top any
	if err := json.Unmarshal([]byte(rawText), &top); err != nil {
		return rawText
	}
	items, _ := top.([]any)
	if items == nil {
		if obj, ok := top.(map[string]any); ok {
			for _, key := range []string{"results", "nodes"} {
				if list, ok := obj[key].([]any); ok {
					items = list
					break
				}
			}
		}
	}
	if len(items) == 0 {
		return fmt.Sprintf("没有匹配 %q 的符号。", search)
	}
	var out []string
	for _, item := range items {
		entry, _ := item.(map[string]any)
		if inner, ok := entry["node"].(map[string]any); ok {
			entry = inner
		}
		n := nodeShape{}
		if v, ok := entry["kind"].(string); ok {
			n.Kind = v
		}
		if v, ok := entry["name"].(string); ok {
			n.Name = v
		}
		if v, ok := entry["qualifiedName"].(string); ok {
			n.QualifiedName = v
		}
		if v, ok := entry["filePath"].(string); ok {
			n.FilePath = v
		}
		if v, ok := entry["file"].(string); ok {
			n.File = v
		}
		if v, ok := entry["path"].(string); ok {
			n.Path = v
		}
		n.Line = entry["line"]
		n.StartLine = entry["startLine"]
		if v, ok := entry["signature"].(string); ok {
			n.Signature = v
		}
		head := fmt.Sprintf("%-10s %s", kindOf(n), nameOf(n))
		if n.Signature != "" {
			head += "\n  " + n.Signature
		}
		out = append(out, fmt.Sprintf("%s\n  %s:%v", head, location(n), lineOf(n)))
	}
	return strings.Join(out, "\n\n")
}

func Extension() *sdk.Extension {
	e := sdk.New("codegraph")

	// indexAvailable is nil until the first probe. Once a call fails it stays
	// false so every later turn does not pay for another doomed CLI run.
	indexAvailable := (*bool)(nil)

	call := func(ctx sdk.Context, args []string, positionalPath bool) (string, bool) {
		if indexAvailable != nil && !*indexAvailable {
			return "codegraph 索引不可用（先前调用已失败）。若已执行 `codegraph init`，用 /codegraph-status 重试。", false
		}
		syncIndex(ctx)
		text, ok := run(ctx, args, positionalPath)
		indexAvailable = &ok
		return text, ok
	}

	e.RegisterTool(sdk.ToolDefinition{
		Name:  "codegraph_query",
		Label: "CodeGraph Query",
		Description: "按名字搜索代码符号（函数/结构体/方法/枚举），返回定义位置。" +
			"比 grep 快且只命中符号定义，不是文本出现。",
		PromptSnippet: "用 codegraph_query 按名字定位符号定义，再用 codegraph_node 看它的调用关系。",
		PromptGuidelines: []string{
			"重构或改动前，先用 codegraph_node 查调用方，确认改动面；grep 找不到调用关系。",
			"codegraph_query 只给定义位置；要看源码和 callers/callees 用 codegraph_node。",
		},
		Parameters: sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"search": map[string]any{"type": "string", "description": "符号名或关键词，支持部分匹配"},
				"limit":  map[string]any{"type": "number", "description": "最大结果数，默认 15"},
				"kind":   map[string]any{"type": "string", "description": "按种类过滤: function / method / struct / enum / class"},
			},
			"required": []string{"search"},
		},
		Execute: func(ctx sdk.Context, params map[string]any) (any, error) {
			args := []string{"query", strParam(params, "search"), "-j", "-l", fmt.Sprint(numParam(params, "limit", 15))}
			if kind := strParam(params, "kind"); kind != "" {
				args = append(args, "-k", kind)
			}
			rawText, ok := call(ctx, args, false)
			if !ok {
				return textResult(rawText, true), nil
			}
			text := formatQuery(rawText, strParam(params, "search"))
			return textResult(clamp(text), false), nil
		},
	})

	e.RegisterTool(sdk.ToolDefinition{
		Name:  "codegraph_node",
		Label: "CodeGraph Node",
		Description: "看一个符号的源码 + 完整调用链（谁调用了它、它调用了谁），或看一个文件的符号表 + 依赖方。" +
			"重构前用它确认改动影响面。",
		Parameters: sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"name":        map[string]any{"type": "string", "description": "符号名；文件模式时传文件路径"},
				"file":        map[string]any{"type": "boolean", "description": "按文件模式解读 name（文件路径）"},
				"symbolsOnly": map[string]any{"type": "boolean", "description": "文件模式：只列符号表 + 依赖方，不含源码"},
			},
			"required": []string{"name"},
		},
		Execute: func(ctx sdk.Context, params map[string]any) (any, error) {
			args := []string{"node", strParam(params, "name")}
			if boolParam(params, "file") {
				args = append(args, "-f")
			}
			if boolParam(params, "symbolsOnly") {
				args = append(args, "--symbols-only")
			}
			text, ok := call(ctx, args, false)
			if !ok {
				return textResult(text, true), nil
			}
			return textResult(clamp(text), false), nil
		},
	})

	e.RegisterTool(sdk.ToolDefinition{
		Name:  "codegraph_explore",
		Label: "CodeGraph Explore",
		Description: "探索一个主题区域：一次性返回相关符号的源码 + 调用路径。" +
			"适合接手陌生模块时建立全局认识。输出可能很大。",
		Parameters: sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"query":    map[string]any{"type": "string", "description": "要探索的主题，如 'compact 压缩流程'"},
				"maxFiles": map[string]any{"type": "number", "description": "最多包含源码的文件数，默认 8"},
			},
			"required": []string{"query"},
		},
		Execute: func(ctx sdk.Context, params map[string]any) (any, error) {
			args := []string{"explore", strParam(params, "query"), "--max-files", fmt.Sprint(numParam(params, "maxFiles", 8))}
			text, ok := call(ctx, args, false)
			if !ok {
				return textResult(text, true), nil
			}
			return textResult(clamp(text), false), nil
		},
	})

	e.Command("codegraph-status", "查看当前项目的 codegraph 索引状态（会重置失败缓存）",
		func(ctx sdk.Context, _ string) error {
			indexAvailable = nil
			text, ok := run(ctx, []string{"status"}, true)
			if ok {
				if len(text) > 400 {
					text = text[:400]
				}
				ctx.Notify(text, "info")
			} else {
				ctx.Notify(text, "warning")
			}
			return nil
		})

	// Report the index state at session start so the model does not try the
	// tools blindly when the CLI or the index is missing.
	e.OnSessionStart(func(ctx sdk.Context, _ map[string]any) (any, error) {
		text, ok := run(ctx, []string{"status"}, true)
		indexAvailable = &ok
		if !ok {
			if len(text) > 200 {
				text = text[:200]
			}
			ctx.Notify("codegraph 不可用: "+text, "warning")
		}
		return nil, nil
	})

	return e
}
