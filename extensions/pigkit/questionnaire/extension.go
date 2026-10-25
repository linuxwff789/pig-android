// Package questionnaire ports Pi's questionnaire extension to Go.
//
// It asks the user one or more multiple-choice questions and returns the
// answers as a tool result. Pi draws a tabbed custom interface; this port asks
// the questions one after another through the host's native selector and input
// dialogs, which keeps the same schema and result shape without a custom
// component.
package questionnaire

import (
	"encoding/json"
	"fmt"
	"strings"

	sdk "github.com/MichaelKinsy/PiG/extensions/sdk"
)

// option is one answer choice.
type option struct {
	Value       string `json:"value"`
	Label       string `json:"label"`
	Description string `json:"description,omitempty"`
}

// question is one prompt.
type question struct {
	ID         string   `json:"id"`
	Label      string   `json:"label,omitempty"`
	Prompt     string   `json:"prompt"`
	Options    []option `json:"options"`
	AllowOther *bool    `json:"allowOther,omitempty"`
}

// answer is one reply.
type answer struct {
	ID        string `json:"id"`
	Value     string `json:"value"`
	Label     string `json:"label"`
	WasCustom bool   `json:"wasCustom"`
	Index     *int   `json:"index,omitempty"`
}

// result is the details payload of a completed questionnaire.
type result struct {
	Questions []question `json:"questions"`
	Answers   []answer   `json:"answers"`
	Cancelled bool       `json:"cancelled"`
}

// otherLabel is the selector entry that opens the free-text input.
const otherLabel = "Type something."

// decode reads one tool input into T through JSON, so the model's arguments
// land in typed fields without hand-written map walking.
func decode[T any](params map[string]any) (T, error) {
	var value T
	raw, err := json.Marshal(params)
	if err != nil {
		return value, err
	}
	err = json.Unmarshal(raw, &value)
	return value, err
}

func errorResult(message string, questions []question) sdk.ToolResult {
	if questions == nil {
		questions = []question{}
	}
	return sdk.ToolResult{
		Content: message,
		Details: result{Questions: questions, Answers: []answer{}, Cancelled: true},
		IsError: true,
	}
}

// ask runs one question through the dialogs and reports the answer.
func ask(ctx sdk.Context, q question, index int) (answer, bool, error) {
	label := q.Label
	if label == "" {
		label = fmt.Sprintf("Q%d", index+1)
	}
	allowOther := q.AllowOther == nil || *q.AllowOther

	values := make([]string, 0, len(q.Options)+1)
	labels := make([]string, 0, len(q.Options)+1)
	for _, opt := range q.Options {
		text := opt.Label
		if text == "" {
			text = opt.Value
		}
		if opt.Description != "" {
			text += " — " + opt.Description
		}
		labels = append(labels, text)
		values = append(values, opt.Value)
	}
	if allowOther {
		labels = append(labels, otherLabel)
		values = append(values, "")
	}
	if len(labels) == 0 {
		return answer{}, false, nil
	}

	title := fmt.Sprintf("%s: %s", label, q.Prompt)
	answerValue, ok, err := ctx.Select(title, labels)
	if err != nil {
		return answer{}, false, err
	}
	if !ok {
		return answer{}, false, nil
	}
	selected, _ := answerValue.(string)

	for i, text := range labels {
		if text != selected {
			continue
		}
		if values[i] == "" && allowOther {
			typedValue, submitted, err := ctx.Input(q.Prompt, "输入自定义答案")
			if err != nil {
				return answer{}, false, err
			}
			if !submitted {
				return answer{}, false, nil
			}
			typed, _ := typedValue.(string)
			typed = strings.TrimSpace(typed)
			if typed == "" {
				typed = "(no response)"
			}
			return answer{ID: q.ID, Value: typed, Label: typed, WasCustom: true}, true, nil
		}
		chosen := q.Options[i]
		text := chosen.Label
		if text == "" {
			text = chosen.Value
		}
		pos := i
		return answer{ID: q.ID, Value: chosen.Value, Label: text, WasCustom: false, Index: &pos}, true, nil
	}
	return answer{}, false, nil
}

func Extension() *sdk.Extension {
	e := sdk.New("questionnaire")

	optionSchema := map[string]any{
		"type": "object",
		"properties": map[string]any{
			"value":       map[string]any{"type": "string", "description": "The value returned when selected"},
			"label":       map[string]any{"type": "string", "description": "Display label for the option"},
			"description": map[string]any{"type": "string", "description": "Optional description shown below label"},
		},
		"required": []string{"value", "label"},
	}

	e.RegisterTool(sdk.ToolDefinition{
		Name:  "questionnaire",
		Label: "Questionnaire",
		Description: "Ask the user one or more questions. Use for clarifying requirements, getting preferences, " +
			"or confirming decisions.",
		PromptGuidelines: []string{
			"用 questionnaire 一次问清多个待确认项，比逐条追问更省时间。",
		},
		Parameters: sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"questions": map[string]any{
					"type":        "array",
					"description": "Questions to ask the user",
					"items": map[string]any{
						"type": "object",
						"properties": map[string]any{
							"id":         map[string]any{"type": "string", "description": "Unique identifier for this question"},
							"label":      map[string]any{"type": "string", "description": "Short contextual label, e.g. 'Scope' (defaults to Q1, Q2)"},
							"prompt":     map[string]any{"type": "string", "description": "The full question text to display"},
							"options":    map[string]any{"type": "array", "description": "Available options to choose from", "items": optionSchema},
							"allowOther": map[string]any{"type": "boolean", "description": "Allow a free-text answer (default: true)"},
						},
						"required": []string{"id", "prompt", "options"},
					},
				},
			},
			"required": []string{"questions"},
		},
		Execute: func(ctx sdk.Context, params map[string]any) (any, error) {
			if !ctx.HasUI() {
				return errorResult("Error: UI not available (running in non-interactive mode)", nil), nil
			}
			input, err := decode[struct {
				Questions []question `json:"questions"`
			}](params)
			if err != nil {
				return errorResult("Error: "+err.Error(), nil), nil
			}
			if len(input.Questions) == 0 {
				return errorResult("Error: No questions provided", nil), nil
			}

			answers := make([]answer, 0, len(input.Questions))
			for i, q := range input.Questions {
				a, ok, err := ask(ctx, q, i)
				if err != nil {
					return errorResult("Error: "+err.Error(), input.Questions), nil
				}
				if !ok {
					// Cancelling any question cancels the questionnaire.
					return sdk.ToolResult{
						Content: "User cancelled the questionnaire.",
						Details: result{Questions: input.Questions, Answers: answers, Cancelled: true},
					}, nil
				}
				answers = append(answers, a)
			}

			lines := make([]string, 0, len(answers))
			for _, a := range answers {
				lines = append(lines, fmt.Sprintf("%s = %s", a.ID, a.Label))
			}
			return sdk.ToolResult{
				Content: "Answers:\n" + strings.Join(lines, "\n"),
				Details: result{Questions: input.Questions, Answers: answers, Cancelled: false},
			}, nil
		},
	})

	return e
}
