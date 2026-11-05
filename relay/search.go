// Session search backs the app's "search history" field: the drawer used to
// filter only the session name and the first user preview it already had in
// memory, so a phrase from an older turn could not be found at all.
//
// The scan reads the JSONL files the relay already owns (pig's session store),
// one line at a time, and keeps only user/assistant text and thinking blocks.
// Images and tool payloads are skipped: they dominate the bytes and matching a
// random base64 fragment is never what the reader meant.
package main

import (
	"bufio"
	"encoding/json"
	"os"
	"sort"
	"strings"
	"time"
)

const (
	// One-rune queries match almost everything and cost a full scan.
	searchMinQueryRunes = 2
	searchMaxResults    = 60
	// Characters kept on each side of the match in a result line. Two lines of
	// the result row must show the match itself, and a wider window would fold
	// it away on a narrow phone.
	searchSnippetRadius = 30
	// A message carrying a screenshot can exceed this; such a line is skipped
	// rather than buffered whole (the text blocks are usually far smaller).
	searchLineBuffer = 4 << 20
	// Newest sessions first, same order the drawer shows.
	searchMaxFiles = 80
)

type sessionSearchHit struct {
	Path      string `json:"path"`
	SessionID string `json:"sessionId"`
	Name      string `json:"name"`
	MessageID string `json:"messageId"`
	Role      string `json:"role"`
	Snippet   string `json:"snippet"`
	At        int64  `json:"at"`
	// "title" or "message": a renamed session matches on its name, which is
	// not repeated in any message line.
	Kind string `json:"kind"`
}

// searchSessions answers relay_search. The scan runs off the client loop: a
// large history takes long enough that blocking the protocol would stall
// streaming output. offset/limit page through the hits; hasMore tells the app
// whether "load more" is worth showing.
func (s *session) searchSessions(id any, query, onlyPath string, offset, limit int) {
	query = strings.TrimSpace(query)
	if len([]rune(query)) < searchMinQueryRunes {
		_ = s.send(frame{
			"type": "response", "id": id, "command": "relay_search",
			"success": true, "data": frame{"results": []any{}, "hasMore": false},
		})
		return
	}
	if limit <= 0 || limit > searchMaxResults {
		limit = searchMaxResults
	}
	if offset < 0 {
		offset = 0
	}
	go func() {
		results, hasMore := searchSessionFiles(s.sessionsDir, &s.metaCache, query, onlyPath, offset, limit)
		_ = s.send(frame{
			"type": "response", "id": id, "command": "relay_search",
			"success": true, "data": frame{"results": results, "hasMore": hasMore},
		})
	}()
}

func searchSessionFiles(dir string, cache *sessionMetaCache, query, onlyPath string, offset, limit int) ([]sessionSearchHit, bool) {
	if limit <= 0 {
		return nil, false
	}
	files := sessionFiles(dir)
	sort.Slice(files, func(i, j int) bool {
		return files[i].info.ModTime().After(files[j].info.ModTime())
	})
	if len(files) > searchMaxFiles {
		files = files[:searchMaxFiles]
	}
	hits := make([]sessionSearchHit, 0, limit)
	seen := 0
	for _, file := range files {
		if onlyPath != "" && file.path != onlyPath {
			continue
		}
		meta := cache.metaFor(file.path, file.info)
		// The title is part of "search history" too: a renamed session keeps
		// its name only in session_info lines, so a content-only scan would
		// miss it entirely.
		name, _ := meta["name"].(string)
		if name != "" && containsFold([]byte(name), []byte(query)) {
			if seen < offset {
				seen++
			} else {
				seen++
				sessionID, _ := meta["id"].(string)
				updated, _ := meta["updated"].(int64)
				hits = append(hits, sessionSearchHit{
					Path:      file.path,
					SessionID: sessionID,
					Name:      name,
					Snippet:   name,
					At:        updated,
					Kind:      "title",
				})
				if len(hits) > limit {
					return hits[:limit], true
				}
			}
		}
		// A single file is read whole even when only a few hits are wanted:
		// the offset is global, so skipping has to happen after matching.
		for _, hit := range searchSessionFile(file.path, meta, query, 1<<30) {
			if seen < offset {
				seen++
				continue
			}
			seen++
			hits = append(hits, hit)
			if len(hits) > limit {
				// One extra match proves there is a next page.
				return hits[:limit], true
			}
		}
	}
	return hits, false
}

func searchSessionFile(path string, meta map[string]any, query string, limit int) []sessionSearchHit {
	if limit <= 0 {
		return nil
	}
	file, err := os.Open(path)
	if err != nil {
		return nil
	}
	defer file.Close()
	needle := []byte(query)
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), searchLineBuffer)
	sessionID, _ := meta["id"].(string)
	name, _ := meta["name"].(string)
	var hits []sessionSearchHit
	for scanner.Scan() {
		line := scanner.Bytes()
		// Cheap byte-level prefilter: unmarshalling every line of every
		// session was the expensive part.
		if !containsFold(line, needle) {
			continue
		}
		var entry struct {
			Type      string `json:"type"`
			ID        string `json:"id"`
			Timestamp string `json:"timestamp"`
			Message   struct {
				Role    string          `json:"role"`
				Content json.RawMessage `json:"content"`
			} `json:"message"`
		}
		if json.Unmarshal(line, &entry) != nil || entry.Type != "message" {
			continue
		}
		// The system preamble repeats tool documentation in every session;
		// matching it would bury the reader's own turns.
		if entry.Message.Role != "user" && entry.Message.Role != "assistant" {
			continue
		}
		text := messageSearchText(entry.Message.Content)
		snippet, ok := snippetAround(text, query, searchSnippetRadius)
		if !ok {
			continue
		}
		var at int64
		if stamp, err := time.Parse(time.RFC3339Nano, entry.Timestamp); err == nil {
			at = stamp.UnixMilli()
		}
		hits = append(hits, sessionSearchHit{
			Path:      path,
			SessionID: sessionID,
			Name:      name,
			MessageID: entry.ID,
			Role:      entry.Message.Role,
			Snippet:   snippet,
			At:        at,
			Kind:      "message",
		})
		if len(hits) >= limit {
			break
		}
	}
	return hits
}

// messageSearchText flattens the content of one message to searchable text.
// A user message may be a plain string; both roles may carry an array of
// blocks where only text/thinking are interesting.
func messageSearchText(raw json.RawMessage) string {
	var plain string
	if json.Unmarshal(raw, &plain) == nil {
		return plain
	}
	var blocks []struct {
		Type string `json:"type"`
		Text string `json:"text"`
	}
	if json.Unmarshal(raw, &blocks) != nil {
		return ""
	}
	var out strings.Builder
	for _, block := range blocks {
		if block.Text == "" {
			continue
		}
		if block.Type != "text" && block.Type != "thinking" {
			continue
		}
		out.WriteString(block.Text)
		out.WriteByte('\n')
	}
	return out.String()
}

// snippetAround returns the match with a little context, newlines flattened.
// Offsets are rune-based so a CJK snippet is not cut mid-character.
func snippetAround(text, query string, radius int) (string, bool) {
	haystack := []rune(text)
	needle := []rune(strings.ToLower(query))
	lower := []rune(strings.ToLower(text))
	index := -1
	for i := 0; i+len(needle) <= len(lower); i++ {
		match := true
		for j := range needle {
			if lower[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			index = i
			break
		}
	}
	if index < 0 {
		return "", false
	}
	start := index - radius
	if start < 0 {
		start = 0
	}
	end := index + len(needle) + radius
	if end > len(haystack) {
		end = len(haystack)
	}
	snippet := strings.Join(strings.Fields(string(haystack[start:end])), " ")
	if start > 0 {
		snippet = "…" + snippet
	}
	if end < len(haystack) {
		snippet += "…"
	}
	return snippet, true
}

// containsFold is a byte-level ASCII case-insensitive search. Bytes of a
// multi-byte UTF-8 character are compared exactly, which is what we want for
// CJK text, and it avoids allocating a lowercased copy of every session line.
func containsFold(haystack, needle []byte) bool {
	if len(needle) == 0 {
		return true
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		match := true
		for j := 0; j < len(needle); j++ {
			a, b := haystack[i+j], needle[j]
			if a >= 'A' && a <= 'Z' {
				a += 'a' - 'A'
			}
			if b >= 'A' && b <= 'Z' {
				b += 'a' - 'A'
			}
			if a != b {
				match = false
				break
			}
		}
		if match {
			return true
		}
	}
	return false
}
