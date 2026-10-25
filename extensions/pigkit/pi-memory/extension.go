// Package memory is a Go port of Pi's pi-memory extension: markdown-backed
// long-term memory, a scratchpad, daily logs, and qmd-powered search.
//
// It keeps three kinds of markdown under ~/.pi/agent/memory (override with
// PI_MEMORY_DIR):
//
//	MEMORY.md          curated long-term facts, decisions, preferences
//	SCRATCHPAD.md      a checklist of things to fix later
//	daily/<date>.md    append-only running logs
//	recovery/<id>.json recoverable records of deleted entries
//
// A snapshot of that material is appended to the system prompt once per
// session, at compaction, and after a long-term write, so the prompt stays
// byte-stable in between and local prefix caches keep working.
package memory

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/MichaelKinsy/PiG/extensions/sdk"
)


// initLocalZone points time.Local at the device timezone.
//
// Termux usually leaves TZ unexported and ships no /usr/share/zoneinfo, so Go
// falls back to UTC while the shell and Node (via Android) report local time.
// Entry stamps would then be hours off and a "today" write could land on
// yesterday. Ask Android for the zone name and load it from Go's bundled
// zoneinfo.zip.
func initLocalZone() {
	if name := strings.TrimSpace(os.Getenv("TZ")); name != "" {
		if loc, err := time.LoadLocation(name); err == nil {
			time.Local = loc
			return
		}
	}
	if time.Local.String() != "UTC" && time.Local.String() != "Local" {
		return
	}
	getprop := "/system/bin/getprop"
	if _, err := os.Stat(getprop); err != nil {
		return
	}
	cctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	out, err := exec.CommandContext(cctx, getprop, "persist.sys.timezone").Output()
	if err != nil {
		return
	}
	if loc, err := time.LoadLocation(strings.TrimSpace(string(out))); err == nil {
		time.Local = loc
	}
}

// snap is the prompt-injected memory block shared by every session.
var snap snapshot

// Extension builds the pi-memory extension; the host calls it once at load.
func Extension() *sdk.Extension {
	initLocalZone()
	ext := sdk.New("pi-memory")
	registerTools(ext)
	registerEvents(ext)
	return ext
}

// strParam reads a string argument.
func strParam(params map[string]any, key string) string {
	if value, ok := params[key].(string); ok {
		return value
	}
	return ""
}

// numParam reads a numeric argument.
func numParam(params map[string]any, key string) *float64 {
	switch value := params[key].(type) {
	case float64:
		return &value
	case int:
		f := float64(value)
		return &f
	case int64:
		f := float64(value)
		return &f
	}
	return nil
}

// sessionShortID is the 8-character session tag written into entries.
func sessionShortID(ctx sdk.Context) string {
	id, err := ctx.GetSessionID()
	if err != nil {
		return "unknown"
	}
	return shortID(id)
}

// okReply is a successful text result with structured details.
func okReply(text string, details map[string]any) sdk.ToolResult {
	return sdk.ToolResult{Content: text, Details: details}
}

// errReply is a failed text result with structured details.
func errReply(text string, details map[string]any) sdk.ToolResult {
	return sdk.ToolResult{Content: text, IsError: true, Details: details}
}

// ── tools ───────────────────────────────────────────────────────────────────

// registerTools installs the seven memory tools.
func registerTools(ext *sdk.Extension) {
	ext.Tool("memory_write",
		strings.Join([]string{
			"Write to memory files. Two targets:",
			"- 'long_term': Write to MEMORY.md (curated durable facts, decisions, preferences). Mode: 'append' or 'overwrite'.",
			"- 'daily': Append to today's daily log (daily/<YYYY-MM-DD>.md). Always appends.",
			"Use this when the user asks you to remember something, or when you learn important preferences/decisions.",
			"Use #tags (e.g. #decision, #preference, #lesson, #bug) and [[links]] (e.g. [[auth-strategy]]) in content to improve searchability.",
		}, "\n"),
		sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"target": map[string]any{
					"type":        "string",
					"enum":        []any{"long_term", "daily"},
					"description": "Where to write: 'long_term' for MEMORY.md, 'daily' for today's daily log",
				},
				"content": map[string]any{
					"type":        "string",
					"description": "Content to write (Markdown)",
				},
				"mode": map[string]any{
					"type":        "string",
					"enum":        []any{"append", "overwrite"},
					"description": "Write mode for long_term target. Default: 'append'. Daily always appends.",
				},
			},
			"required": []any{"target", "content"},
		},
		handleMemoryWrite)

	ext.Tool("scratchpad",
		strings.Join([]string{
			"Manage a checklist of things to fix later or keep in mind. Actions:",
			"- 'add': Add a new unchecked item (- [ ] text)",
			"- 'done': Mark an item as done (- [x] text). Match by substring.",
			"- 'undo': Uncheck a done item back to open. Match by substring.",
			"- 'clear_done': Remove all checked items from the list.",
			"- 'list': Show all items.",
		}, "\n"),
		sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"action": map[string]any{
					"type":        "string",
					"enum":        []any{"add", "done", "undo", "clear_done", "list"},
					"description": "What to do with the checklist",
				},
				"text": map[string]any{
					"type":        "string",
					"description": "Item text for add, or substring to match for done/undo",
				},
			},
			"required": []any{"action"},
		},
		handleScratchpad)

	ext.Tool("memory_read",
		strings.Join([]string{
			"Read a memory file. Targets:",
			"- 'long_term': Read MEMORY.md",
			"- 'scratchpad': Read SCRATCHPAD.md",
			"- 'daily': Read a specific day's log (default: today). Pass date as YYYY-MM-DD.",
			"- 'list': List all daily log files.",
		}, "\n"),
		sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"target": map[string]any{
					"type":        "string",
					"enum":        []any{"long_term", "scratchpad", "daily", "list"},
					"description": "What to read",
				},
				"date": map[string]any{
					"type":        "string",
					"description": "Date for daily log (YYYY-MM-DD). Default: today.",
				},
			},
			"required": []any{"target"},
		},
		handleMemoryRead)

	ext.Tool("memory_forget",
		strings.Join([]string{
			"Delete outdated or incorrect facts from memory. Removes every entry/paragraph",
			"containing the match string (case-insensitive substring) from MEMORY.md, or from",
			"a daily log when target='daily'. Every deletion creates a durable recovery record",
			"whose visible recovery ID can be passed to memory_restore if the deletion was wrong.",
			"Use this when the user corrects a stored fact or a memory is no longer true —",
			"stale entries keep resurfacing in retrieval and cause confidently wrong answers.",
		}, "\n"),
		sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"match": map[string]any{
					"type":        "string",
					"description": "Case-insensitive substring identifying the fact(s) to remove",
				},
				"target": map[string]any{
					"type":        "string",
					"enum":        []any{"long_term", "daily"},
					"description": "Where to delete from: 'long_term' (MEMORY.md, default) or 'daily'",
				},
				"date": map[string]any{
					"type":        "string",
					"description": "Daily log date (YYYY-MM-DD) when target='daily'. Default: today.",
				},
			},
			"required": []any{"match"},
		},
		handleMemoryForget)

	ext.Tool("memory_restore",
		strings.Join([]string{
			"Restore entries removed by memory_forget using the recovery ID returned by that tool.",
			"Restoration is idempotent and appends only missing entries, so later memory writes survive.",
		}, "\n"),
		sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"recoveryId": map[string]any{
					"type":        "string",
					"description": "Recovery ID returned by memory_forget",
				},
			},
			"required": []any{"recoveryId"},
		},
		handleMemoryRestore)

	ext.Tool("memory_search",
		strings.Join([]string{
			"Search across all memory files (MEMORY.md, SCRATCHPAD.md, daily logs).",
			"Modes:",
			"- 'keyword' (default, ~30ms): Fast BM25 search. Best for specific terms, dates, names, #tags, [[links]].",
			"- 'semantic' (~2s): Meaning-based search. Finds related concepts even with different wording.",
			"- 'deep' (~10s): Hybrid search with reranking. Use when other modes don't find what you need.",
			"Semantic/deep need the local embedding model; when embeddings are disabled they fall back to keyword mode.",
			"If the first search doesn't find what you need, try rephrasing or switching modes. " +
				"Keyword mode is best for specific terms; semantic mode finds related concepts even with different wording.",
		}, "\n"),
		sdk.Schema{
			"type": "object",
			"properties": map[string]any{
				"query": map[string]any{
					"type":        "string",
					"description": "Search query",
				},
				"mode": map[string]any{
					"type":        "string",
					"enum":        []any{"keyword", "semantic", "deep"},
					"description": "Search mode. Default: 'keyword'.",
				},
				"limit": map[string]any{
					"type":        "number",
					"description": "Max results (default: 5)",
				},
			},
			"required": []any{"query"},
		},
		handleMemorySearch)

	ext.Tool("memory_status",
		"Report the health of the memory system: where files live, what's stored, "+
			"whether qmd search is available, whether the pi-memory collection exists, "+
			"whether embeddings are ready, and the active configuration. "+
			"Use this when search behaves unexpectedly or to confirm setup.",
		sdk.Schema{
			"type":       "object",
			"properties": map[string]any{},
		},
		handleMemoryStatus)
}

// handleMemoryWrite appends or overwrites a memory file.
func handleMemoryWrite(ctx sdk.Context, params map[string]any) (any, error) {
	ensureDirs()
	target := strParam(params, "target")
	content := strParam(params, "content")
	mode := strParam(params, "mode")
	if mode == "" {
		mode = "append"
	}
	sid := sessionShortID(ctx)
	ts := nowTimestamp()
	stamp := fmt.Sprintf("<!-- %s [%s] -->", ts, sid)

	if strings.TrimSpace(content) == "" {
		return errReply("Error: 'content' must not be empty.", nil), nil
	}
	if target != "daily" && target != "long_term" {
		return errReply(fmt.Sprintf("Error: unknown target %q. Use 'long_term' or 'daily'.", target), nil), nil
	}

	p := memoryPaths()
	filePath := p.longTerm
	previewMode := modeMiddle
	if target == "daily" {
		filePath = mustDailyPath(todayStr())
		previewMode = modeEnd
	}
	existing := readFile(filePath)
	existingPreview := buildPreview(existing, responsePreviewMaxLines, responsePreviewMaxChars, previewMode)
	label := "Existing MEMORY.md preview"
	if target == "daily" {
		label = "Existing daily log preview"
	}
	existingSnippet := ""
	if existingPreview.Text != "" {
		existingSnippet = "\n\n" + formatPreviewBlock(label, existing, previewMode)
	} else {
		existingSnippet = "\n\nMEMORY.md was empty."
		if target == "daily" {
			existingSnippet = "\n\nDaily log was empty."
		}
	}

	var wrote string
	if target == "daily" {
		mode = "append"
		if err := appendEntry(filePath, stamp, content); err != nil {
			return errReply("memory_write error: "+err.Error(), nil), nil
		}
		wrote = "Appended to daily log: " + filePath
	} else if mode == "overwrite" {
		if err := writeFile(filePath, fmt.Sprintf("<!-- last updated: %s [%s] -->\n%s", ts, sid, content)); err != nil {
			return errReply("memory_write error: "+err.Error(), nil), nil
		}
		wrote = "Overwrote MEMORY.md"
	} else {
		mode = "append"
		if err := appendEntry(filePath, stamp, content); err != nil {
			return errReply("memory_write error: "+err.Error(), nil), nil
		}
		wrote = "Appended to MEMORY.md"
	}

	// Long-term writes change ambient background context; daily writes are
	// high-frequency and already echoed in the tool-call history.
	if target == "long_term" {
		snap.markDirty()
	}
	prepareForUpdate()
	scheduleUpdate()

	return okReply(wrote+existingSnippet, map[string]any{
		"path":          filePath,
		"target":        target,
		"mode":          mode,
		"sessionId":     sid,
		"timestamp":     ts,
		"qmdUpdateMode": updateMode(),
		"existingPreview": existingPreview,
	}), nil
}

// mustDailyPath resolves a validated date to its file.
func mustDailyPath(date string) string {
	path, err := dailyPath(date)
	if err != nil {
		return filepath.Join(memoryPaths().daily, date+".md")
	}
	return path
}

// handleScratchpad manages the SCRATCHPAD.md checklist.
func handleScratchpad(ctx sdk.Context, params map[string]any) (any, error) {
	ensureDirs()
	p := memoryPaths()
	action := strParam(params, "action")
	text := strParam(params, "text")
	content := readFile(p.scratchpad)

	switch action {
	case "list":
		if strings.TrimSpace(content) == "" {
			return okReply("SCRATCHPAD.md is empty.", map[string]any{"path": p.scratchpad, "count": 0, "open": 0}), nil
		}
		items := parseScratchpad(content)
		open := 0
		for _, item := range items {
			if !item.Done {
				open++
			}
		}
		return okReply(content, map[string]any{
			"path":  p.scratchpad,
			"count": len(items),
			"open":  open,
		}), nil

	case "add":
		item := strings.TrimSpace(text)
		if item == "" {
			return errReply("Error: 'text' must not be empty when adding an item.", nil), nil
		}
		updated := scratchpadAdd(content, item, fmt.Sprintf("<!-- %s [%s] -->", nowTimestamp(), sessionShortID(ctx)))
		if err := writeFile(p.scratchpad, updated); err != nil {
			return errReply("scratchpad error: "+err.Error(), nil), nil
		}
		return okReply("Added to scratchpad: "+item, map[string]any{"path": p.scratchpad, "text": item}), nil

	case "done", "undo":
		needle := strings.TrimSpace(text)
		if needle == "" {
			return errReply("Error: 'text' must be a non-empty substring when marking items.", nil), nil
		}
		done := action == "done"
		updated, matched := scratchpadToggle(content, needle, done)
		if !matched {
			state := "open"
			if done {
				state = "done"
			}
			return okReply(fmt.Sprintf("No %s item matching %q.", state, needle), map[string]any{
				"path": p.scratchpad, "matched": false,
			}), nil
		}
		if err := writeFile(p.scratchpad, updated); err != nil {
			return errReply("scratchpad error: "+err.Error(), nil), nil
		}
		verb := "Checked off"
		if !done {
			verb = "Reopened"
		}
		return okReply(fmt.Sprintf("%s item matching %q.", verb, needle), map[string]any{
			"path": p.scratchpad, "matched": true, "text": needle,
		}), nil

	case "clear_done":
		updated, removed := scratchpadClearDone(content)
		if removed == 0 {
			return okReply("No done items to clear.", map[string]any{"path": p.scratchpad, "removed": 0}), nil
		}
		if err := writeFile(p.scratchpad, strings.TrimRight(updated, "\n")+"\n"); err != nil {
			return errReply("scratchpad error: "+err.Error(), nil), nil
		}
		return okReply(fmt.Sprintf("Cleared %d done item%s.", removed, plural(removed)), map[string]any{
			"path": p.scratchpad, "removed": removed,
		}), nil
	}

	return errReply(fmt.Sprintf("Error: unknown action %q.", action), nil), nil
}

// plural is the English plural suffix for a count.
func plural(count int) string {
	if count == 1 {
		return ""
	}
	return "s"
}

// handleMemoryRead returns a memory file verbatim.
func handleMemoryRead(_ sdk.Context, params map[string]any) (any, error) {
	ensureDirs()
	p := memoryPaths()
	target := strParam(params, "target")

	switch target {
	case "list":
		entries, err := os.ReadDir(p.daily)
		if err != nil {
			return okReply("No daily logs directory.", map[string]any{}), nil
		}
		var files []string
		for _, entry := range entries {
			if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".md") {
				files = append(files, entry.Name())
			}
		}
		if len(files) == 0 {
			return okReply("No daily logs found.", map[string]any{}), nil
		}
		sortDescending(files)
		return okReply("Daily logs:\n"+bulletList(files), map[string]any{"files": files}), nil

	case "daily":
		date := strParam(params, "date")
		if strings.TrimSpace(date) == "" {
			date = todayStr()
		}
		path, err := dailyPath(date)
		if err != nil {
			return errReply(fmt.Sprintf("Invalid date format: %s. Use YYYY-MM-DD.", date), map[string]any{"date": date}), nil
		}
		content := readFile(path)
		if strings.TrimSpace(content) == "" {
			return okReply(fmt.Sprintf("No daily log for %s.", date), map[string]any{}), nil
		}
		return okReply(content, map[string]any{"path": path, "date": date}), nil

	case "scratchpad":
		content := readFile(p.scratchpad)
		if strings.TrimSpace(content) == "" {
			return okReply("SCRATCHPAD.md is empty or does not exist.", map[string]any{}), nil
		}
		return okReply(content, map[string]any{"path": p.scratchpad}), nil

	case "long_term":
		content := readFile(p.longTerm)
		if strings.TrimSpace(content) == "" {
			return okReply("MEMORY.md is empty or does not exist.", map[string]any{}), nil
		}
		return okReply(content, map[string]any{"path": p.longTerm}), nil
	}

	return errReply(fmt.Sprintf("Error: unknown target %q.", target), nil), nil
}

// sortDescending orders names newest first.
func sortDescending(names []string) {
	for i := 1; i < len(names); i++ {
		for j := i; j > 0 && names[j] > names[j-1]; j-- {
			names[j], names[j-1] = names[j-1], names[j]
		}
	}
}

// bulletList renders names as markdown bullets.
func bulletList(names []string) string {
	var b strings.Builder
	for _, name := range names {
		b.WriteString("- ")
		b.WriteString(name)
		b.WriteString("\n")
	}
	return strings.TrimRight(b.String(), "\n")
}

// handleMemoryForget deletes matching entries and stores an undo record.
func handleMemoryForget(_ sdk.Context, params map[string]any) (any, error) {
	ensureDirs()
	p := memoryPaths()
	match := strParam(params, "match")
	if strings.TrimSpace(match) == "" {
		return errReply("Error: 'match' must not be empty.", nil), nil
	}
	target := strParam(params, "target")
	if target == "" {
		target = "long_term"
	}
	if target != "long_term" && target != "daily" {
		return errReply(fmt.Sprintf("Error: unknown target %q.", target), nil), nil
	}

	filePath := p.longTerm
	recoveryDate := ""
	if target == "daily" {
		recoveryDate = strParam(params, "date")
		if strings.TrimSpace(recoveryDate) == "" {
			recoveryDate = todayStr()
		}
		path, err := dailyPath(recoveryDate)
		if err != nil {
			return errReply(fmt.Sprintf("Invalid date format: %s. Use YYYY-MM-DD.", recoveryDate), map[string]any{"date": recoveryDate}), nil
		}
		filePath = path
	}

	existing := readFile(filePath)
	if strings.TrimSpace(existing) == "" {
		return okReply(fmt.Sprintf("Nothing stored in %s — nothing to forget.", filePath), map[string]any{"path": filePath, "removed": 0}), nil
	}

	updated, removed := forgetBlocks(existing, match)
	if len(removed) == 0 {
		return okReply(fmt.Sprintf("No entries matching %q in %s.", match, filePath), map[string]any{"path": filePath, "removed": 0}), nil
	}

	// Persist the undo payload before mutating the source file: a failed
	// recovery write must never leave an unrecoverable deletion behind.
	record, err := writeRecovery(target, recoveryDate, removed)
	if err != nil {
		return errReply("memory_forget error: could not write recovery record: "+err.Error(), nil), nil
	}
	if err := writeFile(filePath, updated); err != nil {
		return errReply("memory_forget error: "+err.Error(), nil), nil
	}
	snap.markDirty()
	prepareForUpdate()
	scheduleUpdate()

	removedPreview := buildPreview(strings.Join(removed, "\n\n"), responsePreviewMaxLines, responsePreviewMaxChars, modeStart)
	recordPath, _ := recoveryPath(record.ID)
	return okReply(fmt.Sprintf(
		"Removed %d entr%s from %s. Recovery ID: %s. To undo this deletion, call memory_restore with that ID.\n\nRemoved content preview:\n\n%s",
		len(removed), entryWord(len(removed)), filePath, record.ID, removedPreview.Text,
	), map[string]any{
		"path":           filePath,
		"target":         target,
		"removed":        len(removed),
		"recoveryId":     record.ID,
		"recoveryPath":   recordPath,
		"removedPreview": removedPreview,
	}), nil
}

// entryWord is "y" for one entry, "ies" otherwise.
func entryWord(count int) string {
	if count == 1 {
		return "y"
	}
	return "ies"
}

// handleMemoryRestore re-appends entries from a recovery record.
func handleMemoryRestore(_ sdk.Context, params map[string]any) (any, error) {
	ensureDirs()
	id := strParam(params, "recoveryId")
	record, recordPath, found := readRecovery(id)
	if !found {
		return errReply(fmt.Sprintf("No valid recovery record found for ID %s.", id), map[string]any{"recoveryId": id}), nil
	}
	if record.RestoredAt != "" {
		return okReply(fmt.Sprintf("Recovery %s was already restored at %s.", record.ID, record.RestoredAt),
			map[string]any{"recoveryId": record.ID, "restoredAt": record.RestoredAt}), nil
	}

	targetPath := memoryPaths().longTerm
	if record.Target == "daily" {
		targetPath = mustDailyPath(record.Date)
	}
	existing := readFile(targetPath)
	var missing []string
	for _, entry := range record.RemovedContent {
		if !strings.Contains(existing, entry) {
			missing = append(missing, entry)
		}
	}
	if len(missing) > 0 {
		content := strings.Join(missing, "\n\n") + "\n"
		if trimmed := strings.TrimRight(existing, "\n"); strings.TrimSpace(trimmed) != "" {
			content = trimmed + "\n\n" + content
		}
		if err := writeFile(targetPath, content); err != nil {
			return errReply("memory_restore error: "+err.Error(), nil), nil
		}
		snap.markDirty()
		prepareForUpdate()
		scheduleUpdate()
	}

	record.RestoredAt = nowRFC3339()
	if err := writeFile(recordPath, marshalRecord(record)); err != nil {
		return errReply("memory_restore error: "+err.Error(), nil), nil
	}

	text := fmt.Sprintf("Recovery %s was already present in %s; marked as restored.", record.ID, targetPath)
	if len(missing) > 0 {
		text = fmt.Sprintf("Restored %d entr%s to %s.", len(missing), entryWord(len(missing)), targetPath)
	}
	return okReply(text, map[string]any{
		"recoveryId": record.ID,
		"target":     record.Target,
		"path":       targetPath,
		"restored":   len(missing),
	}), nil
}

// handleMemorySearch queries qmd.
func handleMemorySearch(_ sdk.Context, params map[string]any) (any, error) {
	query := strParam(params, "query")
	mode := strParam(params, "mode")
	if mode == "" {
		mode = "keyword"
	}
	if mode != "keyword" && mode != "semantic" && mode != "deep" {
		mode = "keyword"
	}
	// Local embeddings cost ~2.5 GB RSS and minutes of CPU per document, so
	// they are off by default on this device; keyword (BM25) needs no model.
	// Degrade semantic/deep silently instead of loading the embedder.
	if !embedEnabled() {
		mode = "keyword"
	}
	limit := clampLimit(numParam(params, "limit"), 5, 25)

	if !qmdReady() && !detectQmd() {
		return errReply(installInstructions(), nil), nil
	}
	if !checkCollection() && !setupCollection() {
		return errReply("Could not set up qmd pi-memory collection. Check that qmd is working and the memory directory exists.", nil), nil
	}

	results, stderr, err := runSearch(mode, query, limit)
	if err != nil {
		return errReply("memory_search error: "+err.Error(), nil), nil
	}
	needsEmbed := strings.Contains(strings.ToLower(stderr), "need embeddings")
	embedStarted := false
	if needsEmbed {
		embedStarted = ensureEmbed()
	}

	if len(results) == 0 {
		if needsEmbed && (mode == "semantic" || mode == "deep") {
			lines := []string{
				fmt.Sprintf("No results found for %q (mode: %s).", query, mode),
				"",
				"qmd reports missing vector embeddings for one or more documents.",
			}
			if embedStarted {
				lines = append(lines,
					"Embedding has been started in the background — retry the search shortly.",
					"(The very first embed may take longer while the embedding model downloads.)")
			} else {
				lines = append(lines, "Run this once, then retry:", "  qmd embed")
			}
			return okReply(strings.Join(lines, "\n"), map[string]any{
				"mode": mode, "query": query, "count": 0, "needsEmbed": true, "embedStarted": embedStarted,
			}), nil
		}
		return okReply(fmt.Sprintf("No results found for %q (mode: %s).", query, mode), map[string]any{
			"mode": mode, "query": query, "count": 0, "needsEmbed": needsEmbed,
		}), nil
	}

	var blocks []string
	for i, hit := range results {
		parts := []string{fmt.Sprintf("### Result %d", i+1)}
		if path := resultPath(hit); path != "" {
			parts = append(parts, "**File:** "+path)
		}
		if score, ok := hit["score"].(float64); ok {
			parts = append(parts, fmt.Sprintf("**Score:** %v", score))
		}
		if text := resultText(hit); text != "" {
			parts = append(parts, "\n"+text)
		}
		blocks = append(blocks, strings.Join(parts, "\n"))
	}
	return okReply(strings.Join(blocks, "\n\n---\n\n"), map[string]any{
		"mode": mode, "query": query, "count": len(results), "needsEmbed": needsEmbed,
	}), nil
}

// handleMemoryStatus reports configuration and index health.
func handleMemoryStatus(_ sdk.Context, _ map[string]any) (any, error) {
	ensureDirs()
	inv := inventory()

	qmdOK := qmdReady() || detectQmd()
	collectionOK := false
	embeddings := "n/a"
	if qmdOK {
		collectionOK = checkCollection()
		if collectionOK && embedEnabled() {
			embeddings = probeEmbeddings()
		}
	}

	mark := func(ok bool) string {
		if ok {
			return "✓"
		}
		return "✗"
	}
	dailyExtra := ""
	if inv.LatestDaily != "" {
		dailyExtra = " (latest " + inv.LatestDaily + ")"
	}
	lines := []string{
		"# Memory status",
		"",
		"- Memory dir: " + inv.Dir,
		fmt.Sprintf("- MEMORY.md: %d chars", inv.LongTermChars),
		fmt.Sprintf("- Scratchpad: %d open / %d total", inv.ScratchpadOpen, inv.ScratchTotal),
		fmt.Sprintf("- Daily logs: %d%s", inv.DailyCount, dailyExtra),
		"",
		"## Search (qmd)",
		"- qmd available: " + mark(qmdOK),
	}
	if qmdOK {
		lines = append(lines, "- Collection `"+collectionName+"`: "+mark(collectionOK))
		if collectionOK {
			switch {
			case !embedEnabled():
				lines = append(lines, "- Embeddings (semantic/deep): ⏸ disabled (local model off, keyword search only)")
				lines = append(lines, "  - Re-enable with PI_MEMORY_QMD_EMBED=1 if you want semantic search back.")
			case embeddings == "ready":
				lines = append(lines, "- Embeddings (semantic/deep): ✓ ready")
			case embeddings == "missing":
				lines = append(lines, "- Embeddings (semantic/deep): ⚠ missing")
				if ensureEmbed() {
					lines = append(lines, "  - Embedding started in the background — re-run memory_status to confirm.")
				} else {
					lines = append(lines, "  - Run `qmd embed` once to enable semantic/deep search.")
				}
			default:
				lines = append(lines, "- Embeddings (semantic/deep): ? unknown")
				lines = append(lines, "  - Could not verify within the probe timeout; run a semantic search to confirm.")
			}
		} else {
			lines = append(lines, "  - Run a `memory_search` (auto-creates it) or `qmd collection add` manually.")
		}
	} else {
		lines = append(lines, "", installInstructions())
	}

	dirMode := "default"
	if strings.TrimSpace(os.Getenv("PI_MEMORY_DIR")) != "" {
		dirMode = "set"
	}
	lines = append(lines,
		"",
		"## Configuration",
		"- PI_MEMORY_SNAPSHOT: "+snapshotMode(),
		"- PI_MEMORY_QMD_UPDATE: "+updateMode(),
		fmt.Sprintf("- PI_MEMORY_QMD_SEARCH_TIMEOUT_MS: %d", searchTimeout().Milliseconds()),
		"- PI_MEMORY_DIR: "+dirMode,
		"- Exit summary: the Go port writes a handoff before compaction instead of a model prose summary.",
	)

	return okReply(strings.Join(lines, "\n"), map[string]any{
		"dir":          inv.Dir,
		"longTermChars": inv.LongTermChars,
		"scratchTotal": inv.ScratchTotal,
		"scratchpadOpen": inv.ScratchpadOpen,
		"dailyCount":   inv.DailyCount,
		"latestDaily":  inv.LatestDaily,
		"qmd":          qmdOK,
		"collection":   collectionOK,
		"embeddings":   embeddings,
		"snapshotMode": snapshotMode(),
		"qmdUpdateMode": updateMode(),
	}), nil
}


// Limits for the injected context, mirroring the Node original.
const (
	responsePreviewMaxChars = 4000
	responsePreviewMaxLines = 120

	contextLongTermMaxChars     = 4000
	contextLongTermMaxLines     = 150
	contextScratchpadMaxChars   = 2000
	contextScratchpadMaxLines   = 120
	contextDailyMaxChars        = 3000
	contextDailyMaxLines        = 120
	contextSearchMaxChars       = 2500
	contextSearchMaxLines       = 80
	contextMaxChars             = 16000
	truncateMiddleMarker        = "... (truncated) ..."
)

// memoryDir honors PI_MEMORY_DIR, then keeps Pi's path so both harnesses share
// one memory. A PiG-only directory would silently fork the history.
func memoryDir() string {
	if dir := strings.TrimSpace(os.Getenv("PI_MEMORY_DIR")); dir != "" {
		return dir
	}
	home, err := os.UserHomeDir()
	if err != nil {
		home = "~"
	}
	return filepath.Join(home, ".pi", "agent", "memory")
}

// paths are the files the extension reads and writes.
type paths struct {
	dir        string
	longTerm   string
	scratchpad string
	daily      string
	recovery   string
}

// memoryPaths resolves the current layout.
func memoryPaths() paths {
	dir := memoryDir()
	return paths{
		dir:        dir,
		longTerm:   filepath.Join(dir, "MEMORY.md"),
		scratchpad: filepath.Join(dir, "SCRATCHPAD.md"),
		daily:      filepath.Join(dir, "daily"),
		recovery:   filepath.Join(dir, "recovery"),
	}
}

// ensureDirs creates the layout.
func ensureDirs() {
	p := memoryPaths()
	_ = os.MkdirAll(p.daily, 0o755)
	_ = os.MkdirAll(p.recovery, 0o755)
	_ = os.MkdirAll(p.dir, 0o755)
}

// readFile returns the file's text, or "" when it does not exist.
func readFile(path string) string {
	data, err := os.ReadFile(path)
	if err != nil {
		return ""
	}
	return string(data)
}

// writeFile writes a file, creating its directory.
func writeFile(path, content string) error {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return err
	}
	return os.WriteFile(path, []byte(content), 0o644)
}

// ── dates ───────────────────────────────────────────────────────────────────

// dateStr renders a day in local time as YYYY-MM-DD.
func dateStr(t time.Time) string { return t.Format("2006-01-02") }

// todayStr is the local calendar day.
func todayStr() string { return dateStr(time.Now()) }

// yesterdayStr is the local calendar day before today.
func yesterdayStr() string { return dateStr(time.Now().AddDate(0, 0, -1)) }

// nowTimestamp is the `YYYY-MM-DD HH:MM:SS` stamp memory entries carry.
func nowTimestamp() string { return time.Now().Format("2006-01-02 15:04:05") }

// validDailyDate reports whether date is a real YYYY-MM-DD day.
func validDailyDate(date string) bool {
	parsed, err := time.Parse("2006-01-02", date)
	if err != nil {
		return false
	}
	return parsed.Format("2006-01-02") == date
}

// dailyPath maps a date to its log file.
func dailyPath(date string) (string, error) {
	if !validDailyDate(date) {
		return "", fmt.Errorf("Invalid daily date: %s. Expected YYYY-MM-DD.", date)
	}
	return filepath.Join(memoryPaths().daily, date+".md"), nil
}

// shortID is the first eight characters of a session id.
func shortID(id string) string {
	runes := []rune(id)
	if len(runes) > 8 {
		return string(runes[:8])
	}
	return id
}

// ── truncation ──────────────────────────────────────────────────────────────

// truncateMode picks which end of an over-long text survives.
type truncateMode string

const (
	modeStart  truncateMode = "start"
	modeEnd    truncateMode = "end"
	modeMiddle truncateMode = "middle"
)

// truncateLines keeps at most maxLines, honoring the mode.
func truncateLines(lines []string, maxLines int, mode truncateMode) ([]string, bool) {
	if maxLines <= 0 || len(lines) <= maxLines {
		return lines, false
	}
	switch mode {
	case modeEnd:
		return lines[len(lines)-maxLines:], true
	case modeMiddle:
		if maxLines > 1 {
			keep := maxLines - 1
			head := (keep + 1) / 2
			tail := keep / 2
			out := make([]string, 0, maxLines)
			out = append(out, lines[:head]...)
			out = append(out, truncateMiddleMarker)
			if tail > 0 {
				out = append(out, lines[len(lines)-tail:]...)
			}
			return out, true
		}
	}
	return lines[:maxLines], true
}

// truncateText keeps at most maxChars runes, honoring the mode.
func truncateText(text string, maxChars int, mode truncateMode) (string, bool) {
	runes := []rune(text)
	if maxChars <= 0 || len(runes) <= maxChars {
		return text, false
	}
	switch mode {
	case modeEnd:
		return string(runes[len(runes)-maxChars:]), true
	case modeMiddle:
		if maxChars > 10 {
			keep := maxChars - len([]rune(truncateMiddleMarker))
			if keep > 0 {
				head := (keep + 1) / 2
				tail := keep / 2
				return string(runes[:head]) + truncateMiddleMarker + string(runes[len(runes)-tail:]), true
			}
		}
	}
	return string(runes[:maxChars]), true
}

// preview is a truncated view of a text with the counts to describe it.
type preview struct {
	Text       string `json:"text"`
	Truncated  bool   `json:"truncated"`
	TotalLines int    `json:"totalLines"`
	TotalChars int    `json:"totalChars"`
	Lines      int    `json:"lines"`
	Chars      int    `json:"chars"`
}

// buildPreview normalizes content and truncates it.
func buildPreview(content string, maxLines, maxChars int, mode truncateMode) preview {
	normalized := strings.TrimSpace(content)
	if normalized == "" {
		return preview{}
	}
	lines := strings.Split(normalized, "\n")
	keptLines, lineTruncated := truncateLines(lines, maxLines, mode)
	text, charTruncated := truncateText(strings.Join(keptLines, "\n"), maxChars, mode)
	return preview{
		Text:       text,
		Truncated:  lineTruncated || charTruncated,
		TotalLines: len(lines),
		TotalChars: len([]rune(normalized)),
		Lines:      len(strings.Split(text, "\n")),
		Chars:      len([]rune(text)),
	}
}

// formatPreviewBlock renders a labelled preview for a tool reply.
func formatPreviewBlock(label, content string, mode truncateMode) string {
	result := buildPreview(content, responsePreviewMaxLines, responsePreviewMaxChars, mode)
	if result.Text == "" {
		return label + ": empty."
	}
	note := ""
	if result.Truncated {
		note = fmt.Sprintf("\n[preview truncated: showing %d/%d lines, %d/%d chars]",
			result.Lines, result.TotalLines, result.Chars, result.TotalChars)
	}
	return fmt.Sprintf("%s (%d lines, %d chars)\n\n%s%s", label, result.TotalLines, result.TotalChars, result.Text, note)
}

// formatContextSection renders one section of the injected memory block.
func formatContextSection(label, content string, mode truncateMode, maxLines, maxChars int) string {
	result := buildPreview(content, maxLines, maxChars, mode)
	if result.Text == "" {
		return ""
	}
	note := ""
	if result.Truncated {
		note = fmt.Sprintf("\n\n[truncated: showing %d/%d lines, %d/%d chars]",
			result.Lines, result.TotalLines, result.Chars, result.TotalChars)
	}
	return label + "\n\n" + result.Text + note
}

// ── context ─────────────────────────────────────────────────────────────────

// buildMemoryContext renders the block appended to the system prompt. Sections
// are ordered by how urgently they need to be present.
func buildMemoryContext(searchResults string) string {
	ensureDirs()
	p := memoryPaths()
	var sections []string

	// Open scratchpad items come first: they are the "don't forget" list.
	if scratchpad := readFile(p.scratchpad); strings.TrimSpace(scratchpad) != "" {
		var open []scratchpadItem
		for _, item := range parseScratchpad(scratchpad) {
			if !item.Done {
				open = append(open, item)
			}
		}
		if len(open) > 0 {
			section := formatContextSection("## SCRATCHPAD.md (working context)",
				serializeScratchpad(open), modeStart, contextScratchpadMaxLines, contextScratchpadMaxChars)
			if section != "" {
				sections = append(sections, section)
			}
		}
	}

	today := todayStr()
	if path, err := dailyPath(today); err == nil {
		if content := readFile(path); strings.TrimSpace(content) != "" {
			section := formatContextSection(fmt.Sprintf("## Daily log: %s (today)", today),
				content, modeEnd, contextDailyMaxLines, contextDailyMaxChars)
			if section != "" {
				sections = append(sections, section)
			}
		}
	}

	if strings.TrimSpace(searchResults) != "" {
		section := formatContextSection("## Relevant memories (auto-retrieved)",
			searchResults, modeStart, contextSearchMaxLines, contextSearchMaxChars)
		if section != "" {
			sections = append(sections, section)
		}
	}

	if longTerm := readFile(p.longTerm); strings.TrimSpace(longTerm) != "" {
		section := formatContextSection("## MEMORY.md (long-term)",
			longTerm, modeMiddle, contextLongTermMaxLines, contextLongTermMaxChars)
		if section != "" {
			sections = append(sections, section)
		}
	}

	if path, err := dailyPath(yesterdayStr()); err == nil {
		if content := readFile(path); strings.TrimSpace(content) != "" {
			section := formatContextSection(fmt.Sprintf("## Daily log: %s (yesterday)", yesterdayStr()),
				content, modeEnd, contextDailyMaxLines, contextDailyMaxChars)
			if section != "" {
				sections = append(sections, section)
			}
		}
	}

	if len(sections) == 0 {
		return ""
	}
	context := "# Memory\n\n" + strings.Join(sections, "\n\n---\n\n")
	if len([]rune(context)) <= contextMaxChars {
		return context
	}
	result := buildPreview(context, -1, contextMaxChars, modeStart)
	return fmt.Sprintf("%s\n\n[truncated overall context: showing %d/%d chars]", result.Text, result.Chars, result.TotalChars)
}

// ── snapshot ────────────────────────────────────────────────────────────────

// snapshot keeps the injected block byte-stable between checkpoints so local
// prefix caches are not invalidated on every turn.
type snapshot struct {
	mu      sync.Mutex
	text    string
	takenAt string
	onDate  string
	reason  string
	dirty   bool
	built   bool
}

// snapshotMode is "stable" (default) or "per-turn".
func snapshotMode() string {
	if strings.ToLower(strings.TrimSpace(os.Getenv("PI_MEMORY_SNAPSHOT"))) == "per-turn" {
		return "per-turn"
	}
	return "stable"
}

// refresh rebuilds the snapshot.
func (s *snapshot) refresh(reason string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.text = buildMemoryContext("")
	s.takenAt = nowTimestamp()
	s.onDate = todayStr()
	s.reason = reason
	s.dirty = false
	s.built = true
}

// markDirty forces a rebuild on the next turn.
func (s *snapshot) markDirty() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.dirty = true
}

// current returns the snapshot, rebuilding it when stale.
func (s *snapshot) current() (text, caveat string) {
	s.mu.Lock()
	needsRefresh := !s.built || s.dirty || s.onDate != todayStr()
	if needsRefresh {
		s.mu.Unlock()
		reason := "before_agent_start"
		s.mu.Lock()
		if s.built {
			if s.dirty {
				reason = "long_term_write"
			} else {
				reason = "day_rollover"
			}
		}
		s.mu.Unlock()
		s.refresh(reason)
		s.mu.Lock()
	}
	defer s.mu.Unlock()
	if !s.built {
		return "", ""
	}
	caveat = fmt.Sprintf("Snapshot %s at %s. Use memory_read / memory_search for the authoritative latest state; "+
		"recent writes may also be visible in tool-call history.", s.reason, s.takenAt)
	return s.text, caveat
}

// systemPromptHeader is the wording that introduces the memory block.
func systemPromptHeader(caveat, body string) string {
	lines := []string{"## Memory"}
	if caveat != "" {
		lines = append(lines, "("+caveat+")")
	}
	lines = append(lines,
		"The following memory files have been loaded. Use the memory_write tool to persist important information.",
		"- Decisions, preferences, and durable facts → MEMORY.md",
		"- Day-to-day notes and running context → daily/<YYYY-MM-DD>.md",
		"- Things to fix later or keep in mind → scratchpad tool",
		"- Use memory_search to find past context across all memory files (keyword, semantic, or deep search).",
		"- Use #tags (e.g. #decision, #preference) and [[links]] (e.g. [[auth-strategy]]) in memory content to improve future search recall.",
		`- If someone says "remember this," write it immediately.`,
		"",
		body,
	)
	return strings.Join(lines, "\n")
}


// ── scratchpad ──────────────────────────────────────────────────────────────

// scratchpadItem is one checklist line, with the timestamp comment above it.
type scratchpadItem struct {
	Done bool   `json:"done"`
	Text string `json:"text"`
	Meta string `json:"meta"`
}

var (
	scratchpadItemRe    = regexp.MustCompile(`^- \[([ xX])\] (.+)$`)
	scratchpadMetaRe    = regexp.MustCompile(`^<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \[[^\]\r\n]+\] -->$`)
	entryMetaRe         = regexp.MustCompile(`^<!-- (?:(?:last updated: )?\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}|HANDOFF \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}) \[[^\]\r\n]+\] -->$`)
	recoveryIDRe        = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$`)
	blankLinesRe        = regexp.MustCompile(`\n{2,}`)
)

// parseScratchpad reads the checklist, keeping unknown lines out of the result.
func parseScratchpad(content string) []scratchpadItem {
	var items []scratchpadItem
	lines := strings.Split(content, "\n")
	for i, line := range lines {
		match := scratchpadItemRe.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		meta := ""
		if i > 0 && scratchpadMetaRe.MatchString(lines[i-1]) {
			meta = lines[i-1]
		}
		items = append(items, scratchpadItem{
			Done: strings.EqualFold(match[1], "x"),
			Text: match[2],
			Meta: meta,
		})
	}
	return items
}

// serializeScratchpad renders items with a heading.
func serializeScratchpad(items []scratchpadItem) string {
	lines := []string{"# Scratchpad", ""}
	for _, item := range items {
		if item.Meta != "" {
			lines = append(lines, item.Meta)
		}
		checkbox := "[ ]"
		if item.Done {
			checkbox = "[x]"
		}
		lines = append(lines, fmt.Sprintf("- %s %s", checkbox, item.Text))
	}
	return strings.Join(lines, "\n") + "\n"
}

// scratchpadAdd appends one open item without touching existing lines.
func scratchpadAdd(content, text, meta string) string {
	if strings.TrimSpace(content) == "" {
		return serializeScratchpad([]scratchpadItem{{Done: false, Text: text, Meta: meta}})
	}
	base := strings.TrimRight(content, "\n")
	return fmt.Sprintf("%s\n%s\n- [ ] %s\n", base, meta, text)
}

// scratchpadToggle marks the first matching item done or open.
func scratchpadToggle(content, needle string, done bool) (string, bool) {
	lines := strings.Split(content, "\n")
	lower := strings.ToLower(needle)
	for i, line := range lines {
		match := scratchpadItemRe.FindStringSubmatch(line)
		if match == nil {
			continue
		}
		if strings.EqualFold(match[1], "x") == done {
			continue
		}
		if !strings.Contains(strings.ToLower(match[2]), lower) {
			continue
		}
		checkbox := "[ ]"
		if done {
			checkbox = "[x]"
		}
		lines[i] = fmt.Sprintf("- %s %s", checkbox, match[2])
		return strings.Join(lines, "\n"), true
	}
	return content, false
}

// scratchpadClearDone drops every checked item and its timestamp comment.
func scratchpadClearDone(content string) (string, int) {
	var out []string
	removed := 0
	for _, line := range strings.Split(content, "\n") {
		match := scratchpadItemRe.FindStringSubmatch(line)
		if match != nil && strings.EqualFold(match[1], "x") {
			removed++
			if len(out) > 0 && scratchpadMetaRe.MatchString(out[len(out)-1]) {
				out = out[:len(out)-1]
			}
			continue
		}
		out = append(out, line)
	}
	return strings.Join(out, "\n"), removed
}

// ── forgetting ──────────────────────────────────────────────────────────────

// forgetBlocks removes every generated entry that contains match and returns
// the surviving content plus the removed entries. A generated entry runs from a
// timestamp comment to the next one; text before the first comment is split on
// blank lines so hand-written notes still behave sensibly.
func forgetBlocks(content, match string) (string, []string) {
	needle := strings.ToLower(strings.TrimSpace(match))
	if needle == "" {
		return content, nil
	}
	normalized := strings.ReplaceAll(content, "\r\n", "\n")
	normalized = strings.ReplaceAll(normalized, "\r", "\n")
	normalized = strings.TrimPrefix(normalized, "\ufeff")

	var blocks []string
	var current []string
	stamped := false
	flush := func() {
		joined := strings.TrimSpace(strings.Join(current, "\n"))
		if joined == "" {
			return
		}
		if stamped {
			blocks = append(blocks, joined)
			return
		}
		for _, block := range blankLinesRe.Split(joined, -1) {
			if block = strings.TrimSpace(block); block != "" {
				blocks = append(blocks, block)
			}
		}
	}
	for _, line := range strings.Split(normalized, "\n") {
		if entryMetaRe.MatchString(line) {
			flush()
			current = []string{line}
			stamped = true
			continue
		}
		current = append(current, line)
	}
	flush()

	var kept, removed []string
	for _, block := range blocks {
		if strings.Contains(strings.ToLower(block), needle) {
			removed = append(removed, block)
			continue
		}
		kept = append(kept, block)
	}
	if len(removed) == 0 {
		return content, nil
	}
	joined := strings.TrimSpace(strings.Join(kept, "\n\n"))
	if joined == "" {
		return "", removed
	}
	return joined + "\n", removed
}

// ── recovery records ────────────────────────────────────────────────────────

// recoveryRecord is the durable undo payload written before a deletion.
type recoveryRecord struct {
	Version        int      `json:"version"`
	ID             string   `json:"id"`
	CreatedAt      string   `json:"createdAt"`
	Target         string   `json:"target"`
	Date           string   `json:"date,omitempty"`
	RemovedContent []string `json:"removedContent"`
	RestoredAt     string   `json:"restoredAt,omitempty"`
}

// newUUID returns a random version 4 UUID.
func newUUID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		return fmt.Sprintf("%08x-0000-4000-8000-%012x", time.Now().UnixNano(), time.Now().UnixNano()&0xffffffffffff)
	}
	b[6] = (b[6] & 0x0f) | 0x40
	b[8] = (b[8] & 0x3f) | 0x80
	return fmt.Sprintf("%x-%x-%x-%x-%x", b[0:4], b[4:6], b[6:8], b[8:10], b[10:16])
}

// recoveryPath validates an id and maps it to its record file.
func recoveryPath(id string) (string, bool) {
	if !recoveryIDRe.MatchString(strings.ToLower(id)) {
		return "", false
	}
	return filepath.Join(memoryPaths().recovery, strings.ToLower(id)+".json"), true
}

// writeRecovery stores the removed entries before the source file changes.
func writeRecovery(target, date string, removed []string) (recoveryRecord, error) {
	record := recoveryRecord{
		Version:        1,
		ID:             newUUID(),
		CreatedAt:      time.Now().UTC().Format(time.RFC3339Nano),
		Target:         target,
		Date:           date,
		RemovedContent: removed,
	}
	path, ok := recoveryPath(record.ID)
	if !ok {
		return record, fmt.Errorf("Failed to create a valid recovery ID.")
	}
	ensureDirs()
	if err := os.WriteFile(path, []byte(marshalRecord(record)), 0o644); err != nil {
		return record, err
	}
	return record, nil
}

// readRecovery loads and validates a recovery record.
func readRecovery(id string) (recoveryRecord, string, bool) {
	path, ok := recoveryPath(id)
	if !ok {
		return recoveryRecord{}, "", false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return recoveryRecord{}, "", false
	}
	var record recoveryRecord
	if err := json.Unmarshal(data, &record); err != nil {
		return recoveryRecord{}, "", false
	}
	if record.Version != 1 || record.ID != strings.ToLower(id) {
		return recoveryRecord{}, "", false
	}
	if record.Target != "long_term" && record.Target != "daily" {
		return recoveryRecord{}, "", false
	}
	if record.Target == "daily" && !validDailyDate(record.Date) {
		return recoveryRecord{}, "", false
	}
	if len(record.RemovedContent) == 0 {
		return recoveryRecord{}, "", false
	}
	return record, path, true
}

// appendEntry appends a stamped entry to a file, separated by a blank line.
func appendEntry(path, stamp, content string) error {
	existing := readFile(path)
	separator := ""
	if strings.TrimSpace(existing) != "" {
		separator = "\n\n"
	}
	return writeFile(path, existing+separator+stamp+"\n"+content+"\n")
}

// memoryInventory is the cheap on-disk summary memory_status reports.
type memoryInventory struct {
	Dir            string `json:"dir"`
	LongTermChars  int    `json:"longTermChars"`
	ScratchpadOpen int    `json:"scratchpadOpen"`
	ScratchTotal   int    `json:"scratchTotal"`
	DailyCount     int    `json:"dailyCount"`
	LatestDaily    string `json:"latestDaily"`
}

// inventory scans the memory directory without touching qmd.
func inventory() memoryInventory {
	p := memoryPaths()
	longTerm := readFile(p.longTerm)
	items := parseScratchpad(readFile(p.scratchpad))
	inv := memoryInventory{
		Dir:           p.dir,
		LongTermChars: len([]rune(strings.TrimSpace(longTerm))),
		ScratchTotal:  len(items),
	}
	for _, item := range items {
		if !item.Done {
			inv.ScratchpadOpen++
		}
	}
	entries, err := os.ReadDir(p.daily)
	if err != nil {
		return inv
	}
	var names []string
	for _, entry := range entries {
		if !entry.IsDir() && strings.HasSuffix(entry.Name(), ".md") {
			names = append(names, strings.TrimSuffix(entry.Name(), ".md"))
		}
	}
	inv.DailyCount = len(names)
	if len(names) > 0 {
		latest := names[0]
		for _, name := range names[1:] {
			if name > latest {
				latest = name
			}
		}
		inv.LatestDaily = latest
	}
	return inv
}


// qmd is the optional search backend. Everything here degrades to "unavailable"
// when the binary is missing, so the memory tools still work.
const (
	collectionName    = "pi-memory"
	qmdProbeTimeout   = 15 * time.Second
	qmdSetupTimeout   = 10 * time.Second
	qmdUpdateTimeout  = 30 * time.Second
	qmdEmbedTimeout   = 10 * time.Minute
	// embedBackoff keeps a failing/timing-out embed from being respawned on
	// every search. `qmd embed` can wedge (no progress, full CPU) with a
	// pending chunk it never finishes; retrying it every few seconds made the
	// device unusable.
	embedBackoff = 15 * time.Minute
	qmdDefaultSearch  = 60 * time.Second
	qmdAvailabilityMs = 5 * time.Minute
)

var (
	pendingEmbeddingsRe = regexp.MustCompile(`Pending:\s*(\d+)\s+need`)
	ansiCSI = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)
	ansiOSC = regexp.MustCompile(`\x1b\][^\x07]*(\x07|\x1b\\)`)

	qmdMu        sync.Mutex
	qmdAvailable bool
	qmdCheckedAt time.Time
	qmdKnown     bool

	embedMu       sync.Mutex
	embedInFlight bool
	embedPending  bool
	embedLastFail time.Time

	updateMu    sync.Mutex
	updateTimer *time.Timer

	collectionMu     sync.Mutex
	collectionStatus = map[string]struct {
		checkedAt time.Time
		exists    bool
	}{}
)

// stripANSI removes terminal control sequences from qmd output, which may carry
// spinners even in JSON mode.
func stripANSI(text string) string {
	return ansiOSC.ReplaceAllString(ansiCSI.ReplaceAllString(text, ""), "")
}

// execQmd runs the qmd binary with a timeout.
//
// qmd ships as a JavaScript launcher whose shebang is `#!/usr/bin/env node`.
// Android has no /usr/bin/env, so a direct execve fails with ENOEXEC even
// though the file is executable and works from a shell. Node's spawn retries
// through sh; Go's exec does not, so do it explicitly.
func execQmd(timeout time.Duration, args ...string) (string, string, error) {
	bin, lookErr := exec.LookPath("qmd")
	if lookErr != nil {
		return "", "", lookErr
	}

	cctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()

	run := func(useShell bool) (string, string, error) {
		var cmd *exec.Cmd
		if useShell {
			cmd = exec.Command("sh", append([]string{bin}, args...)...)
		} else {
			cmd = exec.Command(bin, args...)
		}
		// Own process group: `qmd embed` is a node launcher that forks the
		// real embedder, so a timeout that kills only the wrapper leaves an
		// orphan spinning at several cores with ~2.5 GB resident.
		cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
		// `qmd embed` batches 32 chunks in one llama.cpp call and only writes
		// them when the whole batch returns. On a phone that is minutes of
		// CPU with all-or-nothing progress, so a timeout throws the work away
		// and the next attempt starts from zero. qmd reads this knob (local
		// patch to dist/store.js); smaller batches commit incrementally.
		if len(args) > 0 && args[0] == "embed" {
			env := os.Environ()
			filtered := env[:0]
			for _, kv := range env {
				if strings.HasPrefix(kv, "QMD_EMBED_BATCH_SIZE=") {
					continue
				}
				filtered = append(filtered, kv)
			}
			cmd.Env = append(filtered, "QMD_EMBED_BATCH_SIZE=4")
		}
		var outBuf, errBuf bytes.Buffer
		cmd.Stdout = &outBuf
		cmd.Stderr = &errBuf
		if err := cmd.Start(); err != nil {
			return "", errBuf.String(), err
		}
		done := make(chan error, 1)
		go func() { done <- cmd.Wait() }()
		select {
		case err := <-done:
			return outBuf.String(), errBuf.String(), err
		case <-cctx.Done():
			_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGTERM)
			select {
			case <-done:
			case <-time.After(2 * time.Second):
				_ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL)
				<-done
			}
			return outBuf.String(), errBuf.String(), cctx.Err()
		}
	}

	out, errOut, err := run(false)
	if err != nil && needsShell(err) {
		out, errOut, err = run(true)
	}
	if err != nil {
		if cctx.Err() == context.DeadlineExceeded || errors.Is(err, context.DeadlineExceeded) {
			return out, errOut, fmt.Errorf("qmd %s timed out after %s", strings.Join(args, " "), timeout)
		}
	}
	return out, errOut, err
}

// needsShell reports whether a direct exec failed because the kernel could not
// run the file itself (bad or missing shebang interpreter).
func needsShell(err error) bool {
	if errors.Is(err, syscall.ENOEXEC) {
		return true
	}
	message := err.Error()
	return strings.Contains(message, "exec format error") ||
		strings.Contains(message, "no such file or directory")
}

// detectQmd reports whether the binary is callable, caching the answer briefly.
func detectQmd() bool {
	qmdMu.Lock()
	if qmdKnown && time.Since(qmdCheckedAt) < qmdAvailabilityMs {
		available := qmdAvailable
		qmdMu.Unlock()
		return available
	}
	qmdMu.Unlock()

	// `qmd collection list` is lighter than `qmd status`, which can probe
	// devices and time out on some systems.
	_, _, err := execQmd(qmdProbeTimeout, "collection", "list")
	available := err == nil

	qmdMu.Lock()
	qmdAvailable = available
	qmdCheckedAt = time.Now()
	qmdKnown = true
	qmdMu.Unlock()
	return available
}

// qmdReady is the cached availability without probing.
func qmdReady() bool {
	qmdMu.Lock()
	defer qmdMu.Unlock()
	return qmdAvailable
}

// checkCollection reports whether the pi-memory collection exists.
func checkCollection() bool {
	collectionMu.Lock()
	if cached, ok := collectionStatus[collectionName]; ok && time.Since(cached.checkedAt) < qmdAvailabilityMs {
		collectionMu.Unlock()
		return cached.exists
	}
	collectionMu.Unlock()

	stdout, _, err := execQmd(qmdSetupTimeout, "collection", "list", "--json")
	exists := false
	if err == nil {
		var entries []any
		if json.Unmarshal([]byte(stdout), &entries) == nil {
			for _, entry := range entries {
				switch value := entry.(type) {
				case string:
					if value == collectionName {
						exists = true
					}
				case map[string]any:
					if name, ok := value["name"].(string); ok && name == collectionName {
						exists = true
					}
				}
			}
		} else {
			exists = strings.Contains(stdout, collectionName)
		}
	}

	collectionMu.Lock()
	collectionStatus[collectionName] = struct {
		checkedAt time.Time
		exists    bool
	}{time.Now(), exists}
	collectionMu.Unlock()
	return exists
}

// setupCollection registers the memory directory with qmd.
func setupCollection() bool {
	if _, _, err := execQmd(qmdSetupTimeout, "collection", "add", memoryDir(), "--name", collectionName); err != nil {
		return false
	}
	// Path contexts are cosmetic; a failure is not fatal.
	contexts := [][2]string{
		{"/daily", "Daily append-only work logs organized by date"},
		{"/", "Curated long-term memory: decisions, preferences, facts, lessons"},
	}
	for _, entry := range contexts {
		_, _, _ = execQmd(qmdSetupTimeout, "context", "add", entry[0], entry[1], "-c", collectionName)
	}
	collectionMu.Lock()
	collectionStatus[collectionName] = struct {
		checkedAt time.Time
		exists    bool
	}{time.Now(), true}
	collectionMu.Unlock()
	return true
}

// updateMode is "background" by default, or "manual" / "off".
func updateMode() string {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("PI_MEMORY_QMD_UPDATE"))) {
	case "manual":
		return "manual"
	case "off":
		return "off"
	}
	return "background"
}

// embedEnabled reports whether the local embedding model may be used.
// Default is OFF: the Qwen3 embedder needs ~2.5 GB RSS and minutes of CPU per
// document on this phone. Set PI_MEMORY_QMD_EMBED=1 to opt back in.
func embedEnabled() bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv("PI_MEMORY_QMD_EMBED"))) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

// ensureEmbed starts an incremental `qmd embed` in the background. It returns
// true when an embed is running or queued.
func ensureEmbed() bool {
	if !embedEnabled() || updateMode() != "background" || !qmdReady() {
		return false
	}
	embedMu.Lock()
	defer embedMu.Unlock()
	if embedInFlight {
		embedPending = true
		return true
	}
	if !embedLastFail.IsZero() && time.Since(embedLastFail) < embedBackoff {
		return false
	}
	embedInFlight = true
	go func() {
		defer func() { _ = recover() }()
		_, _, err := execQmd(qmdEmbedTimeout, "embed")
		embedMu.Lock()
		embedInFlight = false
		if err != nil {
			embedLastFail = time.Now()
		} else {
			embedLastFail = time.Time{}
		}
		pending := embedPending
		embedPending = false
		embedMu.Unlock()
		if pending {
			ensureEmbed()
		}
	}()
	return true
}

// scheduleUpdate queues a `qmd update` shortly after a write.
func scheduleUpdate() {
	if updateMode() != "background" || !qmdReady() {
		return
	}
	updateMu.Lock()
	defer updateMu.Unlock()
	if updateTimer != nil {
		updateTimer.Stop()
	}
	updateTimer = time.AfterFunc(500*time.Millisecond, func() {
		defer func() { _ = recover() }()
		updateMu.Lock()
		updateTimer = nil
		updateMu.Unlock()
		_, _, _ = execQmd(qmdUpdateTimeout, "update")
		ensureEmbed()
	})
}

// prepareForUpdate refreshes qmd availability before a write schedules work.
func prepareForUpdate() bool {
	if updateMode() != "background" {
		return false
	}
	if !qmdReady() && !detectQmd() {
		return false
	}
	return true
}

// searchTimeout reads PI_MEMORY_QMD_SEARCH_TIMEOUT_MS.
func searchTimeout() time.Duration {
	raw := strings.TrimSpace(os.Getenv("PI_MEMORY_QMD_SEARCH_TIMEOUT_MS"))
	if raw == "" {
		return qmdDefaultSearch
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value <= 0 {
		return qmdDefaultSearch
	}
	return time.Duration(value) * time.Millisecond
}

// searchResult is one hit from qmd.
type searchResult map[string]any

// resultPath is the file a hit refers to.
func resultPath(hit searchResult) string {
	for _, key := range []string{"path", "file"} {
		if value, ok := hit[key].(string); ok && value != "" {
			return value
		}
	}
	return ""
}

// resultText is the matched chunk.
func resultText(hit searchResult) string {
	for _, key := range []string{"content", "chunk", "snippet"} {
		if value, ok := hit[key].(string); ok && value != "" {
			return value
		}
	}
	return ""
}

// parseSearchJSON finds the JSON payload inside qmd's stdout, which may be
// preceded by progress output.
func parseSearchJSON(stdout string) ([]searchResult, error) {
	trimmed := strings.TrimSpace(stdout)
	if trimmed == "" || strings.HasPrefix(trimmed, "No results found") {
		return nil, nil
	}
	cleaned := stripANSI(stdout)
	lines := strings.Split(cleaned, "\n")
	start := -1
	for i, line := range lines {
		trimmedLine := strings.TrimLeft(line, " \t")
		if strings.HasPrefix(trimmedLine, "[") || strings.HasPrefix(trimmedLine, "{") {
			start = i
			break
		}
	}
	if start == -1 {
		if len(trimmed) > 200 {
			trimmed = trimmed[:200]
		}
		return nil, fmt.Errorf("Failed to parse qmd output: %s", trimmed)
	}
	payload := []byte(strings.TrimSpace(strings.Join(lines[start:], "\n")))
	var list []searchResult
	if err := json.Unmarshal(payload, &list); err == nil {
		return list, nil
	}
	var object map[string]any
	if err := json.Unmarshal(payload, &object); err != nil {
		return nil, err
	}
	for _, key := range []string{"results", "hits"} {
		if raw, ok := object[key].([]any); ok {
			results := make([]searchResult, 0, len(raw))
			for _, item := range raw {
				if hit, ok := item.(map[string]any); ok {
					results = append(results, hit)
				}
			}
			return results, nil
		}
	}
	return nil, nil
}

// runSearch queries qmd. Keyword mode is BM25, semantic is vector, deep is the
// hybrid query.
func runSearch(mode, query string, limit int) ([]searchResult, string, error) {
	subcommand := "search"
	switch mode {
	case "semantic":
		subcommand = "vsearch"
	case "deep":
		subcommand = "query"
	}
	timeout := searchTimeout()
	stdout, stderr, err := execQmd(timeout,
		subcommand, "--json", "-c", collectionName, "-n", strconv.Itoa(limit), query)
	if err != nil {
		message := strings.TrimSpace(stripANSI(stderr))
		if message == "" {
			message = strings.TrimSpace(stripANSI(err.Error()))
		}
		if strings.Contains(err.Error(), "timed out") {
			message += fmt.Sprintf(" (qmd timed out after %ds — the first semantic/deep search may download or load models; retry shortly)",
				int(timeout.Seconds()))
		}
		return nil, stderr, fmt.Errorf("%s", message)
	}
	results, parseErr := parseSearchJSON(stdout)
	if parseErr != nil {
		return nil, stderr, parseErr
	}
	return results, stderr, nil
}

// probeEmbeddings reports whether vector search is usable.
//
// Pi asked qmd with a 4-second vector query, which never finishes here: the
// CPU-only embedding model needs ~18s to load, so the answer was always
// "unknown". `qmd status` reports the pending-chunk count without loading a
// model, which is both cheaper and more precise.
func probeEmbeddings() string {
	stdout, _, err := execQmd(10*time.Second, "status")
	if err != nil {
		return "unknown"
	}
	match := pendingEmbeddingsRe.FindStringSubmatch(stripANSI(stdout))
	if match == nil {
		return "unknown"
	}
	if strings.TrimSpace(match[1]) == "0" {
		return "ready"
	}
	return "missing"
}

// installInstructions is the help text shown when qmd is absent.
func installInstructions() string {
	return strings.Join([]string{
		"memory_search requires qmd.",
		"",
		"Install qmd (either works):",
		"  npm install -g @tobilu/qmd        # no Bun needed",
		"  bun install -g https://github.com/tobi/qmd   # ensure ~/.bun/bin is on PATH",
		"",
		"The extension auto-creates the collection on next session start.",
		"To set it up manually instead:",
		"  qmd collection add " + memoryDir() + " --name " + collectionName,
		"  qmd embed",
	}, "\n")
}

// clampLimit bounds a requested result count.
func clampLimit(value *float64, fallback, max int) int {
	if value == nil {
		return fallback
	}
	limit := int(*value)
	if limit < 1 {
		return 1
	}
	if limit > max {
		return max
	}
	return limit
}



// controlChars strips bytes that would break a one-line search query.
var controlChars = regexp.MustCompile(`[\x00-\x1f\x7f]`)

// nowRFC3339 is the recovery record timestamp format.
func nowRFC3339() string { return time.Now().UTC().Format(time.RFC3339Nano) }

// marshalRecord renders a recovery record as pretty JSON with a trailing newline.
func marshalRecord(record recoveryRecord) string {
	var buf bytes.Buffer
	encoder := json.NewEncoder(&buf)
	encoder.SetEscapeHTML(false)
	encoder.SetIndent("", "  ")
	if err := encoder.Encode(record); err != nil {
		return "{}\n"
	}
	return buf.String()
}

// registerEvents wires the session lifecycle: index refresh on start, memory
// injection before each turn, a handoff note before compaction, and a local
// handoff note on shutdown.
func registerEvents(ext *sdk.Extension) {
	ext.OnEvent("session_start", func(ctx sdk.Context, _ map[string]any) (any, error) {
		ensureDirs()
		if qmdReady() || detectQmd() {
			if !checkCollection() {
				setupCollection()
			}
			// Catch up on anything written outside this process (another PiG
			// session, a manual edit) so the next search sees it.
			ensureEmbed()
		}
		snap.refresh("session_start")
		return nil, nil
	})

	// The memory block is a prompt section, which is how the Go SDK exposes
	// Pi's `event.systemPrompt` getter: edit the shared options and the host
	// re-renders. Appending to a systemPrompt string does nothing here.
	ext.OnEvent("before_agent_start", func(_ sdk.Context, data map[string]any) (any, error) {
		options, _ := data["systemPromptOptions"].(map[string]any)
		if options == nil {
			return nil, nil
		}
		sections, _ := options["sections"].(*sdk.SystemPromptSections)
		if sections == nil {
			return nil, nil
		}

		var memoryContext, caveat string
		if snapshotMode() == "per-turn" {
			searchResults := ""
			if os.Getenv("PI_MEMORY_NO_SEARCH") != "1" {
				prompt, _ := data["prompt"].(string)
				searchResults = searchRelevantMemories(prompt)
			}
			memoryContext = buildMemoryContext(searchResults)
		} else {
			var text string
			text, caveat = snap.current()
			memoryContext = text
		}
		if memoryContext == "" {
			sections.Delete("memory")
			return nil, nil
		}
		sections.Set("memory", systemPromptHeader(caveat, memoryContext))
		return nil, nil
	})

	ext.OnEvent("session_before_compact", func(ctx sdk.Context, _ map[string]any) (any, error) {
		defer snap.refresh("session_before_compact")
		ensureDirs()

		var parts []string
		if content := readFile(memoryPaths().scratchpad); strings.TrimSpace(content) != "" {
			var open []scratchpadItem
			for _, item := range parseScratchpad(content) {
				if !item.Done {
					open = append(open, item)
				}
			}
			if len(open) > 0 {
				parts = append(parts, "**Open scratchpad items:**")
				for _, item := range open {
					parts = append(parts, "- [ ] "+item.Text)
				}
			}
		}

		todayPath, err := dailyPath(todayStr())
		if err == nil {
			if content := readFile(todayPath); strings.TrimSpace(content) != "" {
				lines := strings.Split(strings.TrimSpace(content), "\n")
				tail := lines
				if len(tail) > 15 {
					tail = tail[len(tail)-15:]
				}
				parts = append(parts, "**Recent daily log context:**\n"+strings.Join(tail, "\n"))
			}
		}
		if len(parts) == 0 {
			return nil, nil
		}

		sid := sessionShortID(ctx)
		handoff := fmt.Sprintf("<!-- HANDOFF %s [%s] -->\n## Session Handoff\n%s",
			nowTimestamp(), sid, strings.Join(parts, "\n"))
		if todayPath != "" {
			existing := strings.TrimRight(readFile(todayPath), "\n")
			separator := ""
			if strings.TrimSpace(existing) != "" {
				separator = "\n\n"
			}
			_ = writeFile(todayPath, existing+separator+handoff+"\n")
		}
		prepareForUpdate()
		scheduleUpdate()
		return nil, nil
	})

}

// searchRelevantMemories returns a short block of keyword hits for the prompt,
// used only in per-turn snapshot mode.
func searchRelevantMemories(prompt string) string {
	if !qmdReady() || strings.TrimSpace(prompt) == "" {
		return ""
	}
	sanitized := strings.TrimSpace(controlChars.ReplaceAllString(prompt, " "))
	runes := []rune(sanitized)
	if len(runes) > 200 {
		sanitized = string(runes[:200])
	}
	if sanitized == "" || !checkCollection() {
		return ""
	}

	type outcome struct {
		results []searchResult
		err     error
	}
	done := make(chan outcome, 1)
	go func() {
		results, _, err := runSearch("keyword", sanitized, 3)
		done <- outcome{results, err}
	}()

	var results []searchResult
	select {
	case result := <-done:
		if result.err != nil {
			return ""
		}
		results = result.results
	case <-time.After(3 * time.Second):
		return ""
	}

	var snippets []string
	for _, hit := range results {
		text := strings.TrimSpace(resultText(hit))
		if text == "" {
			continue
		}
		if path := resultPath(hit); path != "" {
			text = "_" + path + "_\n" + text
		}
		snippets = append(snippets, text)
	}
	return strings.Join(snippets, "\n\n---\n\n")
}
