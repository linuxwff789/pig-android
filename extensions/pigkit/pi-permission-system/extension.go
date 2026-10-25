// Package permissionsystem ports the core of Pi's @gotgenes/pi-permission-system
// extension to Go.
//
// It gates tool calls with a layered policy that is evaluated
// most-restrictive-wins:
//
//	path (cross-cutting) -> external_directory -> per-tool -> bash
//
// Each rule is "allow", "ask" or "deny". "ask" opens a confirmation dialog when
// a UI is present; yoloMode turns every "ask" into "allow" without a dialog.
// A "deny" blocks the call and names the rule that decided.
//
// The config file lives next to the extension, matching the Node original:
//
//	~/.pig/agent/extensions/pi-permission-system/config.json
//	<cwd>/.pig/extensions/pi-permission-system/config.json
package permissionsystem

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

// ── config ───────────────────────────────────────────────────────────────────

// Effect is a rule's decision.
type Effect string

const (
	allow Effect = "allow"
	ask   Effect = "ask"
	deny  Effect = "deny"
)

// rank orders effects by restrictiveness: a larger rank wins a tie.
func (e Effect) rank() int {
	switch e {
	case deny:
		return 2
	case ask:
		return 1
	case allow:
		return 0
	}
	return -1
}

// Rules is one surface's pattern map, kept in file order so equal-specificity
// matches resolve deterministically.
type Rules struct {
	patterns []string
	effects  []Effect
}

// UnmarshalJSON accepts either a single effect string or a pattern map.
func (r *Rules) UnmarshalJSON(data []byte) error {
	var single string
	if err := json.Unmarshal(data, &single); err == nil {
		r.patterns = []string{"*"}
		r.effects = []Effect{Effect(single)}
		return nil
	}
	// Decode into an ordered list first so pattern order survives.
	var ordered []struct {
		key string
		raw json.RawMessage
	}
	dec := json.NewDecoder(strings.NewReader(string(data)))
	tok, err := dec.Token()
	if err != nil {
		return err
	}
	if delim, ok := tok.(json.Delim); !ok || delim != '{' {
		return fmt.Errorf("rules must be an object or a string")
	}
	for dec.More() {
		keyTok, err := dec.Token()
		if err != nil {
			return err
		}
		key, _ := keyTok.(string)
		var raw json.RawMessage
		if err := dec.Decode(&raw); err != nil {
			return err
		}
		ordered = append(ordered, struct {
			key string
			raw json.RawMessage
		}{key, raw})
	}
	for _, entry := range ordered {
		var value string
		if err := json.Unmarshal(entry.raw, &value); err != nil {
			continue // nested map (a directional sibling) is read elsewhere
		}
		r.patterns = append(r.patterns, entry.key)
		r.effects = append(r.effects, Effect(value))
	}
	return nil
}

// rules returns the surface's rules, expanding the bare key into its
// directional spellings so a directionless config keeps its meaning.
func (c *Config) rules(surface string) *Rules {
	if r, ok := c.surfaces[surface]; ok {
		return r
	}
	return &Rules{}
}

// Config is the resolved permission tree plus the extension's own options.
type Config struct {
	Permission       map[string]*Rules `json:"-"`
	DebugLog         bool              `json:"debugLog"`
	PermissionReview bool              `json:"permissionReviewLog"`
	YoloMode         bool              `json:"yoloMode"`
	DoublePress      bool              `json:"doublePressToConfirm"`

	surfaces map[string]*Rules
	sources  []string
}

// rawConfig is the on-disk shape: `permission` holds the surfaces.
type rawConfig struct {
	Permission       map[string]json.RawMessage `json:"permission"`
	DebugLog         bool                       `json:"debugLog"`
	PermissionReview bool                       `json:"permissionReviewLog"`
	YoloMode         bool                       `json:"yoloMode"`
	DoublePress      bool                       `json:"doublePressToConfirm"`
}

func parseConfig(data []byte) (*Config, error) {
	var raw rawConfig
	if err := json.Unmarshal(data, &raw); err != nil {
		return nil, err
	}
	cfg := &Config{
		DebugLog:         raw.DebugLog,
		PermissionReview: raw.PermissionReview,
		YoloMode:         raw.YoloMode,
		DoublePress:      raw.DoublePress,
		surfaces:         map[string]*Rules{},
	}
	for name, blob := range raw.Permission {
		rules := &Rules{}
		if err := json.Unmarshal(blob, rules); err != nil {
			// A non-pattern value (for example an object of directional keys) is
			// not a rule set for this surface.
			continue
		}
		cfg.surfaces[name] = rules
		// A bare `path` or `external_directory` key expands into both
		// directional spellings unless the direction is spelled out.
		if name == "path" || name == "external_directory" {
			for _, direction := range []string{"read", "write"} {
				key := name + "_" + direction
				if _, exists := raw.Permission[key]; !exists {
					cfg.surfaces[key] = rules
				}
			}
		}
	}
	// A directional key that is a nested object of patterns is the same map
	// shape, so it is already parsed above.
	return cfg, nil
}

// configPaths returns the config files in load order, later files winning.
func configPaths(cwd string) []string {
	paths := []string{}
	if env := strings.TrimSpace(os.Getenv("PIG_CUSTOM_PERMISSIONS")); env != "" {
		if filepath.IsAbs(env) {
			paths = append(paths, env)
		} else {
			paths = append(paths, filepath.Join(cwd, env))
		}
	}
	home, err := os.UserHomeDir()
	if err == nil {
		paths = append(paths, filepath.Join(home, ".pig", "agent", "extensions", "pi-permission-system", "config.json"))
	}
	paths = append(paths, filepath.Join(cwd, ".pig", "extensions", "pi-permission-system", "config.json"))
	return paths
}

// loadConfig merges every readable config file. A later provider entry replaces
// an earlier one of the same surface name.
func loadConfig(cwd string) (*Config, error) {
	merged := &Config{surfaces: map[string]*Rules{}}
	var problems []string
	for _, path := range configPaths(cwd) {
		data, err := os.ReadFile(path)
		if err != nil {
			if !os.IsNotExist(err) {
				problems = append(problems, fmt.Sprintf("%s: %v", path, err))
			}
			continue
		}
		if len(strings.TrimSpace(string(data))) == 0 {
			continue
		}
		cfg, err := parseConfig(data)
		if err != nil {
			problems = append(problems, fmt.Sprintf("%s: %v", path, err))
			continue
		}
		merged.sources = append(merged.sources, path)
		for name, rules := range cfg.surfaces {
			merged.surfaces[name] = rules
		}
		// Options come from the last file that sets them.
		merged.DebugLog = cfg.DebugLog || merged.DebugLog
		merged.PermissionReview = cfg.PermissionReview || merged.PermissionReview
		merged.YoloMode = cfg.YoloMode
	}
	if len(problems) > 0 && len(merged.surfaces) == 0 {
		return merged, fmt.Errorf("%s", strings.Join(problems, "; "))
	}
	return merged, nil
}

// ── matching ─────────────────────────────────────────────────────────────────

// globMatch reports whether pattern matches value. `*` matches any run of
// characters, including `/`, and `?` matches one character.
func globMatch(pattern, value string) bool {
	if pattern == "" {
		return value == ""
	}
	// Iterative matcher keeps the common case linear.
	var p, v int
	star, mark := -1, 0
	for v < len(value) {
		switch {
		case p < len(pattern) && (pattern[p] == '?' || pattern[p] == value[v]):
			p++
			v++
		case p < len(pattern) && pattern[p] == '*':
			star = p
			mark = v
			p++
		case star >= 0:
			p = star + 1
			mark++
			v = mark
		default:
			return false
		}
	}
	for p < len(pattern) && pattern[p] == '*' {
		p++
	}
	return p == len(pattern)
}

// specificity counts a pattern's literal characters, so the most specific
// matching rule wins. `*` scores zero and therefore always loses.
func specificity(pattern string) int {
	count := 0
	for i := 0; i < len(pattern); i++ {
		if pattern[i] != '*' && pattern[i] != '?' {
			count++
		}
	}
	return count
}

// Decision is one surface's verdict.
type Decision struct {
	Effect  Effect
	Surface string
	Pattern string
	Value   string
}

// decide evaluates one surface. Among matching patterns the most specific one
// wins; an equal-specificity tie keeps the later pattern.
func decide(surface string, rules *Rules, value string) (Decision, bool) {
	best := Decision{}
	bestScore := -1
	for i, pattern := range rules.patterns {
		if !globMatch(pattern, value) {
			continue
		}
		score := specificity(pattern)
		if score >= bestScore {
			bestScore = score
			best = Decision{Effect: rules.effects[i], Surface: surface, Pattern: pattern, Value: value}
		}
	}
	return best, bestScore >= 0
}

// mostRestrictive folds per-surface decisions: a larger rank wins, and the
// first deny decides the whole call.
func mostRestrictive(decisions []Decision) Decision {
	best := Decision{Effect: allow, Surface: "*", Pattern: "*"}
	for _, d := range decisions {
		if d.Effect.rank() > best.Effect.rank() {
			best = d
		}
	}
	return best
}

// ── tool classification ──────────────────────────────────────────────────────

// pathTools maps a built-in file tool to its access direction.
var pathTools = map[string]string{
	"read":  "read",
	"ls":    "read",
	"find":  "read",
	"grep":  "read",
	"write": "write",
	"edit":  "write",
}

// pathFromInput returns the path a file tool names.
func pathFromInput(input map[string]any) string {
	for _, key := range []string{"path", "file", "filePath", "file_path"} {
		if value, ok := input[key].(string); ok && value != "" {
			return value
		}
	}
	return ""
}

// looksLikePath reports whether a bash token is worth testing as a path.
func looksLikePath(token string) bool {
	if token == "" || strings.HasPrefix(token, "-") {
		return false
	}
	if strings.Contains(token, "/") || strings.HasPrefix(token, "~") || strings.HasPrefix(token, ".") {
		return true
	}
	// A bare filename such as ".env" is a path the agent may read.
	return strings.Contains(token, ".")
}

// bashPaths extracts path-like tokens from a command line. It is a
// best-effort scan: the original parses the shell grammar, which this port does
// not attempt.
func bashPaths(command string) []string {
	separators := func(r rune) bool {
		switch r {
		case '\'', '"', '`', '\n', '\t', ' ', ';', '|', '>', '<', '=', '(', ')', ',', '&':
			return true
		}
		return false
	}
	tokens := strings.FieldsFunc(command, separators)
	var paths []string
	for _, token := range tokens {
		token = strings.Trim(token, "(),`")
		if looksLikePath(token) {
			paths = append(paths, token)
		}
	}
	return paths
}

// expandUser turns a leading ~ into the home directory.
func expandUser(path string) string {
	if strings.HasPrefix(path, "~") {
		if home, err := os.UserHomeDir(); err == nil {
			return filepath.Join(home, strings.TrimPrefix(path, "~"))
		}
	}
	return path
}

// outsideCwd reports whether path resolves outside the working directory.
func outsideCwd(cwd, path string) bool {
	if cwd == "" {
		return false
	}
	absolute := expandUser(path)
	if !filepath.IsAbs(absolute) {
		absolute = filepath.Join(cwd, absolute)
	}
	absolute = filepath.Clean(absolute)
	base := filepath.Clean(cwd)
	if absolute == base {
		return false
	}
	return !strings.HasPrefix(absolute, base+string(filepath.Separator))
}

// ── extension ────────────────────────────────────────────────────────────────

// guard holds the per-session state.
type guard struct {
	// approved remembers a pattern the user approved for this session.
	approved map[string]bool
	cfg      *Config
	mu       sync.Mutex
}

func sessionKey(surface, pattern string) string { return surface + "\x00" + pattern }

// Extension builds the permission system extension.
func Extension() *sdk.Extension {
	e := sdk.New("pi-permission-system")
	g := &guard{approved: map[string]bool{}}

	reload := func(cwd string) {
		cfg, err := loadConfig(cwd)
		g.mu.Lock()
		g.cfg = cfg
		g.mu.Unlock()
		_ = err
	}

	current := func() *Config {
		g.mu.Lock()
		defer g.mu.Unlock()
		if g.cfg == nil {
			return &Config{surfaces: map[string]*Rules{}}
		}
		return g.cfg
	}

	e.OnSessionStart(func(ctx sdk.Context, data map[string]any) (any, error) {
		reload(ctx.Cwd())
		cfg := current()
		if len(cfg.sources) == 0 {
			ctx.Notify("pi-permission-system: 没有找到 config.json，所有操作放行。", "warning")
			return nil, nil
		}
		mode := "enforcing"
		if cfg.YoloMode {
			mode = "yolo"
		}
		ctx.SetStatus("permissions", "🔒 "+mode)
		return nil, nil
	})

	e.OnEvent(sdk.EventToolCall, func(ctx sdk.Context, data map[string]any) (result any, retErr error) {
		// A defect in the policy engine must never wedge the tool loop, so it
		// fails closed with a diagnostic instead of propagating a panic.
		defer func() {
			if r := recover(); r != nil {
				retErr = nil
				result = map[string]any{
					"block":  true,
					"reason": fmt.Sprintf("permission gate error: %v", r),
				}
			}
		}()
		cfg := current()
		if len(cfg.surfaces) == 0 {
			return nil, nil
		}
		toolName, _ := data["toolName"].(string)
		input, _ := data["input"].(map[string]any)
		cwd := ctx.Cwd()

		var decisions []Decision

		// 1. Per-tool surface.
		if d, ok := decide(toolName, cfg.rules(toolName), ""); ok {
			decisions = append(decisions, d)
		}

		// 2/3. Path and external_directory surfaces.
		var paths []string
		direction := "read"
		if dir, isPathTool := pathTools[toolName]; isPathTool {
			direction = dir
			if p := pathFromInput(input); p != "" {
				paths = append(paths, p)
			}
		}
		if toolName == "bash" {
			if command, ok := input["command"].(string); ok {
				paths = append(paths, bashPaths(command)...)
			}
		}
		for _, p := range paths {
			if d, ok := decide("path_"+direction, cfg.rules("path_"+direction), p); ok {
				decisions = append(decisions, d)
			}
			if outsideCwd(cwd, p) {
				if d, ok := decide("external_directory_"+direction, cfg.rules("external_directory_"+direction), p); ok {
					decisions = append(decisions, d)
				}
			}
		}

		// 4. Bash command patterns, tested against the whole command line.
		if toolName == "bash" {
			if command, ok := input["command"].(string); ok {
				if d, ok := decide("bash", cfg.rules("bash"), strings.TrimSpace(command)); ok {
					decisions = append(decisions, d)
				}
			}
		}

		if len(decisions) == 0 {
			return nil, nil
		}
		verdict := mostRestrictive(decisions)
		if verdict.Effect == allow {
			return nil, nil
		}

		reason := fmt.Sprintf("权限规则拒绝: %s 规则 %q 决定 %s", verdict.Surface, verdict.Pattern, verdict.Effect)

		if verdict.Effect == ask {
			if cfg.YoloMode {
				return nil, nil
			}
			key := sessionKey(verdict.Surface, verdict.Pattern)
			g.mu.Lock()
			alreadyApproved := g.approved[key]
			g.mu.Unlock()
			if alreadyApproved {
				return nil, nil
			}
			if !ctx.HasUI() {
				return map[string]any{"block": true, "reason": reason + "（ask 状态需要交互确认，当前没有 UI）"}, nil
			}
			answer, err := ctx.Confirm("权限确认",
				fmt.Sprintf("%s\n\n规则: %s → %s\n对象: %s", toolName, verdict.Surface, verdict.Pattern, verdict.Value))
			ok, _ := answer.(bool)
			if err != nil || !ok {
				return map[string]any{"block": true, "reason": reason}, nil
			}
			g.mu.Lock()
			g.approved[key] = true
			g.mu.Unlock()
			return nil, nil
		}

		return map[string]any{"block": true, "reason": reason}, nil
	})

	e.Command("permission-system", "Configure pi-permission-system logging and yolo-mode behavior",
		func(ctx sdk.Context, args string) error {
			cfg := current()
			arg := strings.ToLower(strings.TrimSpace(args))
			switch arg {
			case "yolo", "yolo on":
				cfg.YoloMode = true
				ctx.Notify("pi-permission-system: yoloMode = true（ask 全部自动放行，deny 仍然生效）", "info")
				return nil
			case "yolo off":
				cfg.YoloMode = false
				ctx.Notify("pi-permission-system: yoloMode = false", "info")
				return nil
			case "reload":
				reload(ctx.Cwd())
				ctx.Notify("pi-permission-system: 配置已重新加载", "info")
				return nil
			case "reset":
				g.mu.Lock()
				g.approved = map[string]bool{}
				g.mu.Unlock()
				ctx.Notify("pi-permission-system: 本会话的临时批准已清空", "info")
				return nil
			}

			surfaces := make([]string, 0, len(cfg.surfaces))
			for name := range cfg.surfaces {
				surfaces = append(surfaces, name)
			}
			sort.Strings(surfaces)
			var lines []string
			mode := "enforcing"
			if cfg.YoloMode {
				mode = "yolo"
			}
			lines = append(lines, "mode: "+mode)
			lines = append(lines, "sources: "+strings.Join(cfg.sources, ", "))
			for _, name := range surfaces {
				rules := cfg.surfaces[name]
				var pairs []string
				for i, pattern := range rules.patterns {
					pairs = append(pairs, fmt.Sprintf("    %-24s %s", pattern, rules.effects[i]))
				}
				lines = append(lines, "  "+name+":")
				lines = append(lines, pairs...)
			}
			lines = append(lines, "", "用法: /permission-system [yolo | yolo off | reload | reset]")
			ctx.Notify(strings.Join(lines, "\n"), "info")
			return nil
		})

	return e
}
