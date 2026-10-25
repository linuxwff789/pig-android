// Package plan is an optional plan-mode extension for PiG.
//
// Plan mode is a read-only exploration mode: the model investigates the code,
// writes a numbered plan, and cannot change anything. Turning the mode off (or
// choosing "执行计划") restores the tools and the model works through the steps,
// marking each one with [DONE:n].
//
// The extension publishes the plan through the app's plan-widget contract
// (docs/plan-widget.md), so the Android app renders it as a native checklist
// card. Any other extension that follows the same contract works too; this one
// is not special.
package plan

import (
	"encoding/json"
	"fmt"
	"sync"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const (
	stateEntry = "plan-state"
	widgetKey  = "plan"
	statusKey  = "plan"
)

type state struct {
	Enabled   bool   `json:"enabled"`
	Executing bool   `json:"executing"`
	Steps     []Step `json:"steps,omitempty"`
	// ToolsBefore is the active set captured when plan mode turned on. It is
	// process-local: after a restart the live set is captured again, so the
	// saved copy would only go stale.
	ToolsBefore []string `json:"-"`
}

var (
	mu sync.Mutex
	st state
)

// mutatingTools are the built-ins that write. Plan mode removes them from the
// active set and also blocks them in tool_call, because pinning alone does not
// cover a tool an extension registers later.
var mutatingTools = map[string]bool{
	"edit": true, "write": true, "patch": true, "apply_patch": true,
	"multi_edit": true, "multiedit": true, "str_replace": true,
	"notebook_edit": true, "create_file": true,
}

// Extension is the factory pig looks up in the package.
func Extension() *sdk.Extension {
	ext := sdk.New("plan")
	ext.Flag("plan", sdk.FlagOptions{
		Description: "以计划模式（只读）启动",
		Type:        sdk.FlagBoolean,
		Default:     false,
	})
	ext.Command("plan", "计划模式：/plan [on|off|go|show|clear|add|done|undo|rm]", handlePlan)
	ext.Command("todos", "显示计划清单与进度", handleTodos)
	ext.OnEvent(sdk.EventSessionStart, onSessionStart)
	ext.OnEvent(sdk.EventAgentStart, onAgentStart)
	ext.OnEvent(sdk.EventBeforeAgentStart, onBeforeAgentStart)
	ext.OnEvent(sdk.EventToolCall, onToolCall)
	ext.OnEvent(sdk.EventTurnEnd, onTurnEnd)
	ext.OnEvent(sdk.EventAgentEnd, onAgentEnd)
	ext.OnEvent(sdk.EventSessionShutdown, func(_ sdk.Context, _ map[string]any) (any, error) {
		mu.Lock()
		st = state{}
		mu.Unlock()
		return nil, nil
	})
	return ext
}

// snapshot copies the state so handlers never read it while it changes.
func snapshot() state {
	mu.Lock()
	defer mu.Unlock()
	out := st
	out.Steps = append([]Step(nil), st.Steps...)
	out.ToolsBefore = append([]string(nil), st.ToolsBefore...)
	return out
}

// applyTools pins the tool set for plan mode and restores it afterwards.
func applyTools(ctx sdk.Context) {
	current := snapshot()
	active, err := ctx.GetActiveTools()
	if err != nil {
		return
	}
	if current.Enabled {
		before := current.ToolsBefore
		if len(before) == 0 {
			before = append([]string(nil), active...)
		}
		kept := make([]string, 0, len(active))
		for _, name := range active {
			if !mutatingTools[name] {
				kept = append(kept, name)
			}
		}
		ctx.SetActiveTools(kept)
		mu.Lock()
		st.ToolsBefore = before
		mu.Unlock()
		return
	}
	if len(current.ToolsBefore) == 0 {
		return
	}
	ctx.SetActiveTools(mergeTools(active, current.ToolsBefore))
	mu.Lock()
	st.ToolsBefore = nil
	mu.Unlock()
}

// mergeTools keeps the current set and re-adds what plan mode removed. Tools
// the user deleted meanwhile are not resurrected: they are absent from both
// lists only if the user removed them while the mode was off, and then
// ToolsBefore is empty anyway.
func mergeTools(active, saved []string) []string {
	seen := make(map[string]bool, len(active)+len(saved))
	out := make([]string, 0, len(active)+len(saved))
	for _, list := range [][]string{active, saved} {
		for _, name := range list {
			if name == "" || seen[name] {
				continue
			}
			seen[name] = true
			out = append(out, name)
		}
	}
	return out
}

// persist writes the state into the session log so a restart, a switch or the
// app's own agent restart keeps the plan.
func persist(ctx sdk.Context) {
	_ = ctx.AppendEntry(stateEntry, snapshot())
}

// render publishes the plan to the app: one JSON line on the plan widget plus a
// progress status chip. Both are keyed, so the rate line from model-speed and
// the permission chip keep working next to them.
func render(ctx sdk.Context) {
	current := snapshot()
	if len(current.Steps) == 0 {
		_ = ctx.SetWidget(widgetKey, []string{}, sdk.WidgetOptions{"placement": "aboveEditor"})
		if current.Enabled {
			ctx.SetStatus(statusKey, "⏸ plan")
		} else {
			ctx.SetStatus(statusKey, "")
		}
		return
	}
	payload := map[string]any{"plan": map[string]any{"steps": current.Steps}}
	if raw, err := json.Marshal(payload); err == nil {
		_ = ctx.SetWidget(widgetKey, []string{string(raw)}, sdk.WidgetOptions{"placement": "aboveEditor"})
	}
	done, total := progress(current.Steps)
	if current.Enabled {
		ctx.SetStatus(statusKey, "⏸ plan")
		return
	}
	ctx.SetStatus(statusKey, fmt.Sprintf("📋 %d/%d", done, total))
}
