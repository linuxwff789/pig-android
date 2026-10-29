// Slash commands: /plan and /todos.
package plan

import (
	"fmt"
	"strconv"
	"strings"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const usage = `用法：/plan [on|off|go|show|clear|add <文字>|done <序号|文字>|undo <序号|文字>|rm <序号>]
  /plan          在计划和普通模式之间切换
  /plan on|off   显式打开/关闭计划模式
  /plan go       结束计划模式并开始按步骤执行
  /plan add …    追加一步
  /plan done 2   勾选第 2 步（也接受文字片段）
  /plan clear    清空清单`

func handlePlan(ctx sdk.Context, args string) error {
	args = strings.TrimSpace(args)
	if args == "" {
		setEnabled(ctx, !snapshot().Enabled)
		return nil
	}
	fields := strings.Fields(args)
	switch fields[0] {
	case "on":
		setEnabled(ctx, true)
	case "off":
		setEnabled(ctx, false)
	case "go", "execute", "run":
		startExecution(ctx)
	case "show", "list", "steps":
		showPlan(ctx, true)
	case "clear":
		clearPlan(ctx)
	case "add":
		addStep(ctx, strings.TrimSpace(strings.TrimPrefix(args, fields[0])))
	case "done":
		mark(ctx, fields[1:], true)
	case "undo":
		mark(ctx, fields[1:], false)
	case "rm", "remove":
		removeStep(ctx, fields[1:])
	default:
		ctx.Notify(usage, "info")
	}
	return nil
}

func handleTodos(ctx sdk.Context, _ string) error {
	showPlan(ctx, true)
	return nil
}

func setEnabled(ctx sdk.Context, enabled bool) {
	mu.Lock()
	if enabled != st.Enabled {
		st.ToolsBefore = nil // recomputed from the live tool set below
	}
	st.Enabled = enabled
	// Turning plan mode off also stops an execution: otherwise Executing stays
	// true, the execution prompt keeps being injected and the progress chip
	// never clears.
	st.Executing = false
	if enabled {
		st.Steps = nil // a new planning round starts from a clean sheet
	}
	mu.Unlock()
	applyTools(ctx)
	persist(ctx)
	render(ctx)
	if enabled {
		ctx.Notify("计划模式：只读。edit/write 已禁用，bash 只跑只读命令。", "info")
	} else {
		ctx.Notify("计划模式已关闭，工具已恢复。", "info")
	}
}

func startExecution(ctx sdk.Context) {
	steps := snapshot().Steps
	if len(steps) == 0 {
		ctx.Notify("还没有计划。先让模型给出一个以 Plan: 开头的编号计划，或用 /plan add 添加。", "warning")
		return
	}
	mu.Lock()
	st.Enabled = false
	st.Executing = true
	mu.Unlock()
	applyTools(ctx)
	persist(ctx)
	render(ctx)
	ctx.Notify("开始执行计划：工具已恢复，模型会按 [DONE:n] 标记进度。", "info")
	_ = ctx.SendMessage(
		"plan-execute",
		executionPrompt(steps),
		true,
		sdk.SendMessageOptions{TriggerTurn: sdk.Bool(true), DeliverAs: "followUp"},
	)
}

func addStep(ctx sdk.Context, text string) {
	text = strings.TrimSpace(text)
	if text == "" {
		ctx.Notify("用法：/plan add <一步要做什么>", "warning")
		return
	}
	mu.Lock()
	st.Steps = append(st.Steps, Step{N: nextNumber(st.Steps), Text: cleanStepText(text)})
	mu.Unlock()
	persist(ctx)
	render(ctx)
}

func mark(ctx sdk.Context, args []string, done bool) {
	if len(args) == 0 {
		ctx.Notify("用法：/plan done <序号|文字>", "warning")
		return
	}
	changed := 0
	mu.Lock()
	if number, err := strconv.Atoi(args[0]); err == nil {
		for i := range st.Steps {
			if st.Steps[i].N == number && st.Steps[i].Done != done {
				st.Steps[i].Done = done
				changed++
			}
		}
	} else {
		needle := strings.ToLower(strings.Join(args, " "))
		for i := range st.Steps {
			if st.Steps[i].Done != done && strings.Contains(strings.ToLower(st.Steps[i].Text), needle) {
				st.Steps[i].Done = done
				changed++
			}
		}
	}
	mu.Unlock()
	if changed == 0 {
		ctx.Notify("没有匹配的步骤。", "warning")
		return
	}
	persist(ctx)
	render(ctx)
}

func removeStep(ctx sdk.Context, args []string) {
	if len(args) == 0 {
		ctx.Notify("用法：/plan rm <序号>", "warning")
		return
	}
	number, err := strconv.Atoi(args[0])
	if err != nil {
		ctx.Notify("序号必须是数字。", "warning")
		return
	}
	mu.Lock()
	kept := st.Steps[:0]
	for _, step := range st.Steps {
		if step.N != number {
			kept = append(kept, step)
		}
	}
	st.Steps = kept
	mu.Unlock()
	persist(ctx)
	render(ctx)
}

func clearPlan(ctx sdk.Context) {
	mu.Lock()
	st.Steps = nil
	st.Executing = false
	mu.Unlock()
	persist(ctx)
	render(ctx)
}

func showPlan(ctx sdk.Context, notify bool) {
	current := snapshot()
	if len(current.Steps) == 0 {
		ctx.Notify("还没有计划。", "info")
		return
	}
	var b strings.Builder
	done, total := progress(current.Steps)
	mode := "执行中"
	switch {
	case current.Enabled:
		mode = "计划模式"
	case !current.Executing:
		mode = "待执行"
	}
	fmt.Fprintf(&b, "计划 %d/%d · %s\n", done, total, mode)
	for _, step := range current.Steps {
		box := "☐"
		if step.Done {
			box = "☑"
		}
		fmt.Fprintf(&b, "%s %d. %s\n", box, step.N, step.Text)
	}
	if !notify {
		return
	}
	ctx.Notify(strings.TrimRight(b.String(), "\n"), "info")
}
