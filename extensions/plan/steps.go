// Plan extraction: the model writes a numbered list under a "Plan:" header,
// the extension turns it into steps and tracks [DONE:n] markers.
//
// Extraction beats a custom tool here: the model already writes plans that way
// when asked, so no extra tool call, no extra schema in the prompt, and the
// same text works with any model.
package plan

import (
	"fmt"
	"regexp"
	"strings"
)

const maxSteps = 200

// Step is one plan item. N is the number the model uses in [DONE:n]; it stays
// stable when items are added or completed so markers never shift.
type Step struct {
	N    int    `json:"n"`
	Text string `json:"text"`
	Done bool   `json:"done"`
}

var (
	planHeaderWithTitle = regexp.MustCompile(`(?i)^\s*#{0,6}\s*\**\s*plan\s*:\s*\**\s*(.*?)\s*$`)
	planHeaderBare      = regexp.MustCompile(`(?i)^\s*#{0,6}\s*\**\s*plan\s*\**\s*$`)
	numberedLine        = regexp.MustCompile(`^\s*(\d+)[.)]\s+(.+?)\s*$`)
	doneMarker          = regexp.MustCompile(`(?i)\[DONE:(\d+)\]`)
	markdownNoise       = strings.NewReplacer("**", "", "__", "", "`", "", "*", "")
)

// extractSteps reads the first "Plan:" section of an assistant message.
// It returns nil when the message has no plan section or no numbered item.
func extractSteps(message string) []Step {
	lines := strings.Split(message, "\n")
	start := -1
	for index, line := range lines {
		if planHeaderWithTitle.MatchString(line) || planHeaderBare.MatchString(line) {
			start = index
			break
		}
	}
	if start < 0 {
		return nil
	}
	steps := make([]Step, 0, 8)
	for _, line := range lines[start+1:] {
		match := numberedLine.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		text := cleanStepText(match[2])
		if len([]rune(text)) < 3 {
			continue
		}
		steps = append(steps, Step{N: len(steps) + 1, Text: text})
		if len(steps) >= maxSteps {
			break
		}
	}
	if len(steps) == 0 {
		return nil
	}
	return steps
}

// doneSteps lists the step numbers an assistant message marked as finished.
func doneSteps(message string) []int {
	matches := doneMarker.FindAllStringSubmatch(message, -1)
	seen := make(map[int]bool, len(matches))
	out := make([]int, 0, len(matches))
	for _, match := range matches {
		number := 0
		for _, ch := range match[1] {
			if ch < '0' || ch > '9' {
				number = 0
				break
			}
			number = number*10 + int(ch-'0')
		}
		if number > 0 && !seen[number] {
			seen[number] = true
			out = append(out, number)
		}
	}
	return out
}

// cleanStepText removes the decoration a model puts around a step so the card
// shows words, not markdown.
func cleanStepText(raw string) string {
	text := markdownNoise.Replace(raw)
	text = strings.TrimSpace(text)
	text = strings.TrimRight(text, ":：。.,，;；")
	text = strings.TrimSpace(text)
	if runes := []rune(text); len(runes) > 100 {
		text = strings.TrimSpace(string(runes[:99])) + "…"
	}
	return text
}

// progress counts finished steps.
func progress(steps []Step) (done, total int) {
	total = len(steps)
	for _, step := range steps {
		if step.Done {
			done++
		}
	}
	return done, total
}

// statusLine is the chip text for the current mode. A finished plan keeps its
// card (so the work is still visible) but must not leave "16/16" in the status
// bar forever after plan mode/execution ended.
func statusLine(enabled, executing bool, steps []Step) string {
	if enabled {
		return "⏸ plan"
	}
	if executing {
		done, total := progress(steps)
		return fmt.Sprintf("📋 %d/%d", done, total)
	}
	return ""
}
