// Package subagent adds a `subagent` tool: the model can delegate one
// self-contained task to a child agent session and get its answer back.
//
// The tool surface follows the `subagent` tool of pi-subagent-feather (MIT,
// derived from @eggmasonvalue/pi-subagent): task, label, model, thinking,
// tools, cwd, timeoutMs, plus a receipt with status, turns, tokens and tool
// count. The execution model is PiG's own: instead of spawning another pi
// process, the child runs in this one through ctx.CreateAgentSession, so it
// shares the provider runtime and costs no extra ~60-250 MB process on a phone.
//
// The child is in-memory: it never writes to ~/.pig/agent/sessions, so a
// delegation with a long transcript does not grow the phone's session store.
// `resume` from the original is therefore not supported.
//
// Set PIG_SUBAGENT=off to hide the tool without uninstalling the extension.
package subagent

import (
	"context"
	"fmt"
	"os"
	"strings"
	"time"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const (
	toolName       = "subagent"
	defaultTimeout = 600 * time.Second
	maxTimeout     = 1800 * time.Second
	maxResultChars = 12000
)

// internalTools are never inherited by a child: a delegation that inherits the
// delegator can fork without bound. Naming them in `tools` opts in explicitly.
var internalTools = map[string]bool{toolName: true}

func Extension() *sdk.Extension {
	e := sdk.New("subagent")

	e.RegisterTool(sdk.ToolDefinition{
		Name:  toolName,
		Label: "Subagent",
		Description: "Delegate one self-contained task to an isolated child agent and wait for its answer. " +
			"The child has its own context window, so use it for work that would flood this one " +
			"(searching a large tree, reading many files, running a long investigation). " +
			"Parallel calls are independent. The child does not inherit this tool unless you name it in `tools`.",
		PromptSnippet: "Delegate one bounded task to a child agent with its own context window.",
		PromptGuidelines: []string{
			"subagent runs a separate agent that cannot see this conversation: put everything it needs (paths, constraints, desired output shape) in `task`.",
			"subagent returns a receipt plus the child's final answer; delegate more than once in one turn to run independent tasks in parallel.",
		},
		Parameters: sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"task": map[string]any{
					"type":        "string",
					"minLength":   1,
					"description": "Everything the child needs: the goal, the paths, the constraints and the shape of the answer you want.",
				},
				"label": map[string]any{
					"type":        "string",
					"maxLength":   64,
					"description": "Short name for the receipt, e.g. scout-auth.",
				},
				"model": map[string]any{
					"type":        "string",
					"description": "Optional `provider/id` or model id for the child. Omit to inherit the session's model.",
				},
				"thinking": map[string]any{
					"type":        "string",
					"enum":        []string{"off", "minimal", "low", "medium", "high", "xhigh", "max"},
					"description": "Optional thinking level for the child.",
				},
				"tools": map[string]any{
					"type":        "array",
					"items":       map[string]any{"type": "string"},
					"uniqueItems": true,
					"description": "Tool allowlist for the child (e.g. [\"read\",\"bash\"] for read-only scouting). Omit to inherit every tool except subagent; [] means no tools.",
				},
				"cwd": map[string]any{
					"type":        "string",
					"description": "Working directory for the child. Defaults to the session's.",
				},
				"timeoutMs": map[string]any{
					"type":        "integer",
					"minimum":     1000,
					"maximum":     int(maxTimeout / time.Millisecond),
					"description": "Give up after this many milliseconds; the partial answer is returned.",
				},
			},
			"required": []string{"task"},
		},
		Execute: run,
	})

	return e
}

func run(ctx sdk.Context, params map[string]any) (any, error) {
	if strings.EqualFold(os.Getenv("PIG_SUBAGENT"), "off") {
		return nil, fmt.Errorf("subagent is disabled (PIG_SUBAGENT=off)")
	}
	task := strings.TrimSpace(str(params["task"]))
	if task == "" {
		return nil, fmt.Errorf("subagent: task must not be blank")
	}
	label := strings.TrimSpace(str(params["label"]))
	if label == "" {
		label = "child"
	}

	options := sdk.CreateAgentSessionOptions{
		Cwd:      strings.TrimSpace(str(params["cwd"])),
		InMemory: true,
	}
	if level := strings.TrimSpace(str(params["thinking"])); level != "" {
		options.ThinkingLevel = level
	}
	if model := strings.TrimSpace(str(params["model"])); model != "" {
		provider, id := splitModel(model)
		found, err := ctx.ModelRegistry().Find(provider, id)
		if err != nil {
			return nil, fmt.Errorf("subagent: model %q: %w", model, err)
		}
		options.Model = found
	}
	tools, err := resolveTools(ctx, params["tools"])
	if err != nil {
		return nil, err
	}
	options.Tools = tools

	timeout := defaultTimeout
	if ms, ok := number(params["timeoutMs"]); ok && ms > 0 {
		timeout = time.Duration(ms) * time.Millisecond
		if timeout > maxTimeout {
			timeout = maxTimeout
		}
	}

	session, err := ctx.CreateAgentSession(options)
	if err != nil {
		return nil, fmt.Errorf("subagent: could not start a child session: %w", err)
	}
	defer session.Dispose()
	// The child loads the same resources; its extensions start headless.
	_ = session.BindExtensions()

	started := time.Now()
	runCtx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	promptErr := session.Prompt(runCtx, task)
	elapsed := time.Since(started)
	timedOut := runCtx.Err() == context.DeadlineExceeded

	state, stateErr := session.State()
	answer := ""
	if stateErr == nil && state.LastAssistantText != nil {
		answer = strings.TrimSpace(*state.LastAssistantText)
	}
	turns, input, output := usage(session)

	status := "done"
	switch {
	case timedOut:
		status = "timeout"
	case promptErr != nil:
		status = "failed"
	case answer == "":
		status = "empty"
	}

	var b strings.Builder
	fmt.Fprintf(&b, "%s · %s · %d turns · ↑%d ↓%d tok · %d tools · %s",
		status, label, turns, input, output, countTools(tools, state), elapsed.Round(time.Second))
	if model := modelLabel(options.Model); model != "" {
		fmt.Fprintf(&b, " · %s", model)
	}
	b.WriteString("\n")
	if promptErr != nil && !timedOut {
		fmt.Fprintf(&b, "error: %v\n", promptErr)
	}
	if timedOut {
		fmt.Fprintf(&b, "stopped after %s; the answer below is partial\n", timeout)
	}
	if len(session.ExtensionErrors) > 0 {
		fmt.Fprintf(&b, "child extension errors: %s\n", strings.Join(session.ExtensionErrors, "; "))
	}
	if answer == "" {
		b.WriteString("(no answer)\n")
	} else {
		b.WriteString(truncate(answer, maxResultChars))
		b.WriteString("\n")
	}
	return b.String(), nil
}

// resolveTools returns the child's allowlist: the caller's list when given,
// otherwise every active tool except the delegator.
func resolveTools(ctx sdk.Context, raw any) ([]string, error) {
	if list, ok := raw.([]any); ok {
		out := make([]string, 0, len(list))
		for _, item := range list {
			if name := strings.TrimSpace(str(item)); name != "" {
				out = append(out, name)
			}
		}
		return out, nil
	}
	active, err := ctx.GetActiveTools()
	if err != nil {
		return nil, fmt.Errorf("subagent: could not read the active tools: %w", err)
	}
	out := make([]string, 0, len(active))
	for _, name := range active {
		if !internalTools[name] {
			out = append(out, name)
		}
	}
	return out, nil
}

// usage sums the child's assistant messages: turns and billable tokens.
func usage(session *sdk.AgentSession) (turns int, input, output int) {
	messages, err := session.Messages()
	if err != nil {
		return 0, 0, 0
	}
	for _, msg := range messages {
		if role, _ := msg["role"].(string); role != "assistant" {
			continue
		}
		turns++
		u, _ := msg["usage"].(map[string]any)
		input += intOf(u["input"])
		output += intOf(u["output"])
	}
	return turns, input, output
}

func countTools(allowlist []string, state sdk.AgentSessionState) int {
	if len(allowlist) > 0 {
		return len(allowlist)
	}
	return len(state.ActiveTools)
}

func modelLabel(model any) string {
	obj, ok := model.(map[string]any)
	if !ok {
		return ""
	}
	provider, _ := obj["provider"].(string)
	id, _ := obj["id"].(string)
	return strings.TrimPrefix(provider+"/"+id, "/")
}

func splitModel(raw string) (provider, id string) {
	if index := strings.Index(raw, "/"); index > 0 {
		return raw[:index], raw[index+1:]
	}
	return "", raw
}

func truncate(text string, limit int) string {
	if len(text) <= limit {
		return text
	}
	half := limit / 2
	return text[:half] + fmt.Sprintf("\n… %d chars omitted …\n", len(text)-limit) + text[len(text)-half:]
}

func str(value any) string {
	s, _ := value.(string)
	return s
}

func number(value any) (int64, bool) {
	switch v := value.(type) {
	case int:
		return int64(v), true
	case int64:
		return v, true
	case float64:
		return int64(v), true
	}
	return 0, false
}

func intOf(value any) int {
	n, _ := number(value)
	return int(n)
}
