// Package modelspeed ports Pi's model-speed extension to Go.
//
// It shows the model output rate (tokens/s) in the footer, and exposes /speed
// to switch the display mode or print the last turn's statistics.
package modelspeed

import (
	"encoding/json"
	"fmt"
	"math"
	"strings"
	"time"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

const statusKey = "model-speed"

type speedMode string

const (
	modeFull    speedMode = "full"
	modeCompact speedMode = "compact"
	modeOff     speedMode = "off"
)

// Stats is the last completed assistant message's measurement.
type Stats struct {
	Model        string
	OutputTokens float64
	GenerationMs int64
	TTFTMs       int64
	TotalMs      int64
	TPS          float64
}

// state is per-extension state. Pi keeps it in module scope, which PiG resets
// on every session replacement (D70), so a factory-scoped value behaves the same.
type state struct {
	mode      speedMode
	last      *Stats
	active    bool
	startedAt time.Time
	firstTok  time.Time
	lastDelta time.Time
	estTokens float64
	rendered  time.Time
}

func (s *state) reset() {
	s.active = false
	s.startedAt = time.Time{}
	s.firstTok = time.Time{}
	s.lastDelta = time.Time{}
	s.estTokens = 0
}

// estimateTokens counts a delta: CJK/full-width characters cost about one
// token, everything else about a quarter.
func estimateTokens(text string) float64 {
	var tokens float64
	for _, r := range text {
		if r > 0x2e80 {
			tokens++
		} else {
			tokens += 0.25
		}
	}
	return tokens
}

func fmtRate(tps float64) string {
	if math.IsNaN(tps) || math.IsInf(tps, 0) || tps <= 0 {
		return "0"
	}
	if tps >= 100 {
		return fmt.Sprintf("%.0f", tps)
	}
	return fmt.Sprintf("%.1f", tps)
}

func fmtMs(ms int64) string {
	if ms >= 1000 {
		return fmt.Sprintf("%.2fs", float64(ms)/1000)
	}
	return fmt.Sprintf("%dms", ms)
}

func fmtTokens(tokens float64) string {
	if tokens < 10 {
		return fmt.Sprintf("%.1f", tokens)
	}
	return fmt.Sprintf("%.0f", math.Round(tokens))
}

func (s *state) render(ctx sdk.Context) {
	if s.mode == modeOff {
		ctx.SetStatus(statusKey, "")
		return
	}
	s.rendered = time.Now()

	live := s.active && !s.firstTok.IsZero()
	genMs := int64(0)
	tokens := s.estTokens
	if live {
		genMs = time.Since(s.firstTok).Milliseconds()
	} else if s.last != nil {
		genMs = s.last.GenerationMs
		tokens = s.last.OutputTokens
	}
	tps := 0.0
	if genMs > 0 {
		tps = tokens / float64(genMs) * 1000
	}
	approx := ""
	if live {
		approx = "~"
	}

	var b strings.Builder
	fmt.Fprintf(&b, "⚡ %s%s tok/s", approx, fmtRate(tps))

	if s.mode == modeFull {
		var parts []string
		if tokens > 0 {
			parts = append(parts, fmtTokens(tokens)+" tok")
		}
		if !live && s.last != nil && s.last.TTFTMs > 0 {
			parts = append(parts, "ttft "+fmtMs(s.last.TTFTMs))
		}
		if len(parts) > 0 {
			b.WriteString(" · " + strings.Join(parts, " · "))
		}
	}
	ctx.SetStatus(statusKey, b.String())
}

// str reads a string field from an event payload.
func str(m map[string]any, key string) string {
	v, _ := m[key].(string)
	return v
}

func num(m map[string]any, key string) (float64, bool) {
	switch v := m[key].(type) {
	case float64:
		return v, true
	case int:
		return float64(v), true
	case int64:
		return float64(v), true
	case json.Number:
		f, err := v.Float64()
		return f, err == nil
	}
	return 0, false
}

// assistantMessageEvent returns the message_update payload's nested event.
func assistantMessageEvent(data map[string]any) map[string]any {
	ev, _ := data["assistantMessageEvent"].(map[string]any)
	return ev
}

// messageField returns a nested field of the payload's message object.
func messageField(data map[string]any, key string) (any, bool) {
	msg, ok := data["message"].(map[string]any)
	if !ok {
		return nil, false
	}
	v, ok := msg[key]
	return v, ok
}

func Extension() *sdk.Extension {
	e := sdk.New("model-speed")
	s := &state{mode: modeFull}

	e.OnEvent(sdk.EventSessionStart, func(ctx sdk.Context, _ map[string]any) (any, error) {
		if s.last != nil {
			s.render(ctx)
		}
		return nil, nil
	})

	e.OnEvent(sdk.EventMessageStart, func(ctx sdk.Context, data map[string]any) (any, error) {
		msg, _ := data["message"].(map[string]any)
		if str(msg, "role") != "assistant" {
			return nil, nil
		}
		s.reset()
		s.active = true
		s.startedAt = time.Now()
		ctx.SetStatus(statusKey, "⚡ …")
		return nil, nil
	})

	e.OnEvent(sdk.EventMessageUpdate, func(ctx sdk.Context, data map[string]any) (any, error) {
		if !s.active {
			return nil, nil
		}
		ev := assistantMessageEvent(data)
		if ev == nil {
			return nil, nil
		}
		switch str(ev, "type") {
		case "text_delta", "thinking_delta", "toolcall_delta":
		default:
			return nil, nil
		}
		// An empty delta (the empty toolcall_start tail) would claim the first
		// token and flash "0 tok/s", so it does not count.
		delta, _ := ev["delta"].(string)
		if delta == "" {
			return nil, nil
		}
		now := time.Now()
		if s.firstTok.IsZero() {
			s.firstTok = now
		}
		s.lastDelta = now
		s.estTokens += estimateTokens(delta)

		// Throttle redraws instead of painting every delta.
		if now.Sub(s.rendered) >= 120*time.Millisecond {
			s.render(ctx)
		}
		return nil, nil
	})

	e.OnEvent(sdk.EventMessageEnd, func(ctx sdk.Context, data map[string]any) (any, error) {
		msg, _ := data["message"].(map[string]any)
		if str(msg, "role") != "assistant" {
			return nil, nil
		}
		s.active = false
		now := time.Now()

		outputTokens := s.estTokens
		if usage, ok := msg["usage"].(map[string]any); ok {
			if v, ok := num(usage, "output"); ok && v > 0 {
				outputTokens = v
			}
		}
		end := s.lastDelta
		if end.IsZero() {
			end = now
		}
		genMs := end.Sub(s.startedAt).Milliseconds()
		if !s.firstTok.IsZero() {
			genMs = end.Sub(s.firstTok).Milliseconds()
		}
		ttft := int64(0)
		if !s.firstTok.IsZero() {
			ttft = s.firstTok.Sub(s.startedAt).Milliseconds()
		}
		model := str(msg, "model")
		if model == "" {
			model = ctx.Model()
		}
		if model == "" {
			model = "?"
		}
		tps := 0.0
		if genMs > 0 {
			tps = outputTokens / float64(genMs) * 1000
		}
		s.last = &Stats{
			Model:        model,
			OutputTokens: outputTokens,
			GenerationMs: genMs,
			TTFTMs:       ttft,
			TotalMs:      now.Sub(s.startedAt).Milliseconds(),
			TPS:          tps,
		}
		s.render(ctx)
		return nil, nil
	})

	stop := func(ctx sdk.Context, _ map[string]any) (any, error) {
		s.active = false
		return nil, nil
	}
	e.OnEvent(sdk.EventAgentEnd, stop)
	e.OnEvent(sdk.EventSessionShutdown, stop)

	e.Command("speed", "显示/切换模型输出速率 (full | compact | off)",
		func(ctx sdk.Context, args string) error {
			switch arg := strings.ToLower(strings.TrimSpace(args)); arg {
			case "full", "compact", "off":
				s.mode = speedMode(arg)
				s.render(ctx)
				ctx.Notify("model-speed: "+string(s.mode), "info")
				return nil
			case "toggle":
				if s.mode == modeOff {
					s.mode = modeFull
				} else {
					s.mode = modeOff
				}
				s.render(ctx)
				ctx.Notify("model-speed: "+string(s.mode), "info")
				return nil
			}

			if s.last == nil {
				ctx.Notify("还没有可用的速率数据，先发一条消息吧。", "warning")
				return nil
			}
			last := s.last
			ctx.Notify(strings.Join([]string{
				"model: " + last.Model,
				"rate:  " + fmtRate(last.TPS) + " tok/s",
				"out:   " + fmtTokens(last.OutputTokens) + " tok",
				"ttft:  " + fmtMs(last.TTFTMs),
				"gen:   " + fmtMs(last.GenerationMs),
				"total: " + fmtMs(last.TotalMs),
				"mode:  " + string(s.mode),
			}, "\n"), "info")
			return nil
		})

	return e
}
