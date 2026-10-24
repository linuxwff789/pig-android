// Event handlers: prompt injection, tool guarding, progress and persistence.
package plan

import (
	"encoding/json"
	"fmt"
	"strings"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const planPrompt = `[计划模式]
你现在处于只读计划模式。
- 只能读取、搜索、查询：edit/write 工具不可用，bash 只接受只读命令。
- 不要修改仓库、安装依赖或启动服务；需要变更时写进计划。
- 需要澄清时先用 questionnaire 工具提问。
- 结束时在回复里给出以 "Plan:" 开头的编号步骤列表，每步一句话：

Plan:
1. 第一步要做什么
2. 第二步要做什么

之后用户可以选择开始执行。`

func executionPrompt(steps []Step) string {
	var b strings.Builder
	b.WriteString("[执行计划 · 全工具已恢复]\n剩余步骤：\n")
	remaining := 0
	for _, step := range steps {
		if step.Done {
			continue
		}
		remaining++
		fmt.Fprintf(&b, "%d. %s\n", step.N, step.Text)
	}
	if remaining == 0 {
		b.WriteString("（全部完成）\n")
	}
	b.WriteString("\n按顺序执行，每完成一步就在回复里带上 [DONE:n]（n 是步骤编号）。")
	return b.String()
}

// onSessionStart restores the plan of this session. The app restarts pig when a
// context window is pinned, and a switch replaces the process, so the library
// state alone is not enough: the last `plan-state` entry is the source of truth.
func onSessionStart(ctx sdk.Context, _ map[string]any) (any, error) {
	restored := loadState(ctx)
	if flag, err := ctx.GetFlag("plan"); err == nil && truthy(flag) {
		restored.Enabled = true
		restored.Executing = false
	}
	mu.Lock()
	st = restored
	st.ToolsBefore = nil
	mu.Unlock()
	applyTools(ctx)
	render(ctx)
	return nil, nil
}

func onBeforeAgentStart(_ sdk.Context, data map[string]any) (any, error) {
	options, _ := data["systemPromptOptions"].(map[string]any)
	if options == nil {
		return nil, nil
	}
	sections, _ := options["sections"].(*sdk.SystemPromptSections)
	if sections == nil {
		return nil, nil
	}
	current := snapshot()
	switch {
	case current.Enabled:
		sections.Set("plan_mode", planPrompt)
	case current.Executing && len(current.Steps) > 0:
		sections.Set("plan_mode", executionPrompt(current.Steps))
	default:
		sections.Delete("plan_mode")
	}
	return nil, nil
}

// onToolCall is the hard half of plan mode. The soft half is the prompt; a
// model that forgets it must not be able to write by accident.
func onToolCall(ctx sdk.Context, data map[string]any) (any, error) {
	if !snapshot().Enabled {
		return nil, nil
	}
	toolName, _ := data["toolName"].(string)
	if mutatingTools[toolName] {
		return map[string]any{
			"block":  true,
			"reason": "计划模式：工具 " + toolName + " 会修改内容，已拦截。把这一步写进计划，用户确认后再执行。",
		}, nil
	}
	if toolName == "bash" {
		input, _ := data["input"].(map[string]any)
		command, _ := input["command"].(string)
		if !SafeCommand(command) {
			return map[string]any{
				"block":  true,
				"reason": "计划模式：只允许只读命令，已拦截：" + firstLine(command) + "\n需要写入时请写进计划。",
			}, nil
		}
	}
	return nil, nil
}

func onTurnEnd(ctx sdk.Context, data map[string]any) (any, error) {
	if !snapshot().Executing {
		return nil, nil
	}
	numbers := doneSteps(sdk.MessageText(data))
	if len(numbers) == 0 {
		return nil, nil
	}
	finishedAll := false
	mu.Lock()
	changed := false
	for _, number := range numbers {
		for i := range st.Steps {
			if st.Steps[i].N == number && !st.Steps[i].Done {
				st.Steps[i].Done = true
				changed = true
			}
		}
	}
	if changed && len(st.Steps) > 0 {
		finishedAll = true
		for _, step := range st.Steps {
			if !step.Done {
				finishedAll = false
				break
			}
		}
		if finishedAll {
			st.Executing = false
		}
	}
	total := len(st.Steps)
	mu.Unlock()
	if !changed {
		return nil, nil
	}
	persist(ctx)
	render(ctx)
	if finishedAll {
		applyTools(ctx)
		ctx.Notify(fmt.Sprintf("计划已完成（%d 步）。/plan clear 清空清单。", total), "info")
	}
	return nil, nil
}

// onAgentEnd extracts the plan the model just wrote and offers the next step.
// In RPC mode ctx.HasUI() is true and Select reaches the app as a dialog; in a
// headless run it degrades to a notification.
func onAgentEnd(ctx sdk.Context, data map[string]any) (any, error) {
	if retry, _ := data["willRetry"].(bool); retry {
		return nil, nil
	}
	if !snapshot().Enabled {
		return nil, nil
	}
	text, stopReason := lastAssistant(data)
	// An aborted or failed reply is a fragment: never let it overwrite the plan
	// the user already has. This is what keeps the card intact when the user
	// stops generation halfway through writing a plan.
	if stopReason == "aborted" || stopReason == "error" || stopReason == "pending" {
		return nil, nil
	}
	steps := extractSteps(text)
	if len(steps) == 0 {
		return nil, nil
	}
	mu.Lock()
	st.Steps = steps
	mu.Unlock()
	persist(ctx)
	render(ctx)

	if !ctx.HasUI() {
		ctx.Notify("计划已生成。/plan go 开始执行，/plan off 退出计划模式。", "info")
		return nil, nil
	}
	choiceValue, ok, err := ctx.Select(
		fmt.Sprintf("计划已生成（%d 步），接下来？", len(steps)),
		[]string{"执行计划", "留在计划模式", "细化计划"},
	)
	if err != nil || !ok {
		return nil, nil
	}
	choice, _ := choiceValue.(string)
	switch {
	case strings.HasPrefix(choice, "执行"):
		startExecution(ctx)
	case strings.HasPrefix(choice, "细化"):
		refinementValue, submitted, err := ctx.Editor("补充计划要求", "")
		refinement, _ := refinementValue.(string)
		if err == nil && submitted && strings.TrimSpace(refinement) != "" {
			_ = ctx.SendMessage(
				"plan-refine",
				strings.TrimSpace(refinement),
				true,
				sdk.SendMessageOptions{TriggerTurn: sdk.Bool(true), DeliverAs: "followUp"},
			)
		}
	}
	return nil, nil
}

// lastAssistant picks the last assistant message out of agent_end's payload and
// reports why it stopped, so a partial (aborted) reply can be told apart from a
// finished one.
func lastAssistant(data map[string]any) (string, string) {
	messages, _ := data["messages"].([]any)
	for index := len(messages) - 1; index >= 0; index-- {
		message, ok := messages[index].(map[string]any)
		if !ok {
			continue
		}
		if role, _ := message["role"].(string); role != "assistant" {
			continue
		}
		text := sdk.MessageText(map[string]any{"message": message})
		if text == "" {
			continue
		}
		stopReason, _ := message["stopReason"].(string)
		return text, stopReason
	}
	return "", ""
}

// loadState reads the newest plan-state entry of the session.
func loadState(ctx sdk.Context) state {
	entries, err := ctx.GetEntries()
	if err != nil {
		return state{}
	}
	for index := len(entries) - 1; index >= 0; index-- {
		var entry struct {
			Type       string          `json:"type"`
			CustomType string          `json:"customType"`
			Data       json.RawMessage `json:"data"`
		}
		if json.Unmarshal(entries[index], &entry) != nil {
			continue
		}
		if entry.Type != "custom" || entry.CustomType != stateEntry {
			continue
		}
		var restored state
		if json.Unmarshal(entry.Data, &restored) != nil {
			continue
		}
		return restored
	}
	return state{}
}

func truthy(value any) bool {
	switch typed := value.(type) {
	case bool:
		return typed
	case string:
		return typed == "true" || typed == "1"
	case float64:
		return typed != 0
	}
	return false
}

func firstLine(text string) string {
	if index := strings.IndexByte(text, '\n'); index >= 0 {
		return text[:index]
	}
	return text
}

func nextNumber(steps []Step) int {
	highest := 0
	for _, step := range steps {
		if step.N > highest {
			highest = step.N
		}
	}
	return highest + 1
}
