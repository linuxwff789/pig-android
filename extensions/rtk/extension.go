// Package rtk ports Pi's rtk extension to Go.
//
// It rewrites bash commands into their `rtk` equivalents before they run, which
// is what makes `cat`, `grep`, `git diff` and friends return compressed output
// instead of the whole file. All of the rewriting lives in `rtk rewrite`; this
// extension only calls it and hands the new command back to the host.
//
// Behaviour matches the TypeScript original:
//
//   - the tool is only rewritten when it is `bash`;
//   - a command already starting with "rtk " is left alone;
//   - RTK_DISABLED=1 disables the rewrite;
//   - `rtk rewrite` runs with a 2 s timeout and passes the command through on
//     any error, timeout or unexpected exit code;
//   - exit code 0 (rewrite) and 3 (advisory rewrite) both mutate the command;
//   - `rtk` older than 0.23.0 is not supported and the extension says so in the
//     status line instead of rewriting.
//
// The Go port exists so no Node.js runtime is needed for a 30-line hook.
package rtk

import (
	"os"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"time"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const (
	statusKey      = "rtk"
	rewriteTimeout = 2000 * time.Millisecond
	minMajor       = 0
	minMinor       = 23
)

var versionPattern = regexp.MustCompile(`(\d+)\.(\d+)\.(\d+)`)

// probe is the result of the one-time `rtk --version` check.
type probe struct {
	done   bool
	reason string
}

var state struct {
	mu sync.Mutex
	p  probe
}

// ensureProbe runs `rtk --version` once per session and records why the
// extension cannot rewrite. It returns the reason, empty when rtk is usable.
func ensureProbe(ctx sdk.Context) string {
	state.mu.Lock()
	defer state.mu.Unlock()
	if state.p.done {
		return state.p.reason
	}
	state.p.done = true

	result, err := ctx.ExecWithOptions("rtk", []string{"--version"}, sdk.ExecOptions{
		Timeout: float64(rewriteTimeout / time.Millisecond),
	})
	if err != nil || result == nil || result.ExitCode != 0 {
		state.p.reason = "rtk binary not found in PATH"
		return state.p.reason
	}
	version := strings.TrimPrefix(strings.TrimSpace(result.Stdout), "rtk ")
	match := versionPattern.FindStringSubmatch(version)
	if match == nil {
		return ""
	}
	major, _ := strconv.Atoi(match[1])
	minor, _ := strconv.Atoi(match[2])
	if major == minMajor && minor < minMinor {
		state.p.reason = "rtk " + version + " is too old (need >= 0.23.0)"
	}
	return state.p.reason
}

// rewriteCommand returns the rewritten command, or "" to pass it through.
func rewriteCommand(ctx sdk.Context, command string) string {
	result, err := ctx.ExecWithOptions("rtk", []string{"rewrite", command}, sdk.ExecOptions{
		Timeout: float64(rewriteTimeout / time.Millisecond),
	})
	if err != nil || result == nil || result.Killed {
		return ""
	}
	if result.ExitCode != 0 && result.ExitCode != 3 {
		return ""
	}
	return strings.TrimSpace(result.Stdout)
}

func Extension() *sdk.Extension {
	e := sdk.New("rtk")

	reportUnavailable := func(ctx sdk.Context, reason string) {
		if reason != "" {
			ctx.SetStatus(statusKey, "RTK disabled: "+reason)
		}
	}

	e.OnSessionStart(func(ctx sdk.Context, _ map[string]any) (any, error) {
		if reason := ensureProbe(ctx); reason != "" {
			reportUnavailable(ctx, reason)
		}
		return nil, nil
	})

	e.OnEvent(sdk.EventToolCall, func(ctx sdk.Context, data map[string]any) (any, error) {
		// Fail open: a defect here must never block a command.
		defer func() { _ = recover() }()

		if name, _ := data["toolName"].(string); name != "bash" {
			return nil, nil
		}
		input, ok := data["input"].(map[string]any)
		if !ok {
			return nil, nil
		}
		command, _ := input["command"].(string)
		if strings.TrimSpace(command) == "" {
			return nil, nil
		}
		if strings.HasPrefix(command, "rtk ") {
			return nil, nil
		}
		if os.Getenv("RTK_DISABLED") == "1" {
			return nil, nil
		}
		if reason := ensureProbe(ctx); reason != "" {
			return nil, nil
		}

		rewritten := rewriteCommand(ctx, command)
		if rewritten != "" && rewritten != command {
			input["command"] = rewritten
		}
		return nil, nil
	})

	return e
}
