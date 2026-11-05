package main

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeSearchSession(t *testing.T, dir, project, name string, lines []string) string {
	t.Helper()
	path := filepath.Join(dir, project, name)
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	body := strings.Join(lines, "\n") + "\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func sessionLine(t *testing.T, role, id, text string) string {
	t.Helper()
	payload := map[string]any{
		"type":      "message",
		"id":        id,
		"timestamp": "2026-11-05T10:00:00.000Z",
		"message": map[string]any{
			"role": role,
			"content": []map[string]any{
				{"type": "text", "text": text},
			},
		},
	}
	encoded, err := json.Marshal(payload)
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func sessionHeader(t *testing.T, id string) string {
	t.Helper()
	encoded, err := json.Marshal(map[string]any{
		"type": "session", "version": 3, "id": id, "cwd": "/tmp",
	})
	if err != nil {
		t.Fatal(err)
	}
	return string(encoded)
}

func TestSearchSessionFilesFindsUserAndAssistantText(t *testing.T) {
	s := newTestSession(t)
	path := writeSearchSession(t, s.sessionsDir, "p", "a.jsonl", []string{
		sessionHeader(t, "sess-a"),
		sessionLine(t, "system", "m0", "system preamble mentions Needle"),
		sessionLine(t, "user", "m1", "请帮我找 needle 这个词"),
		sessionLine(t, "assistant", "m2", "The answer mentions NEEDLE twice"),
	})
	hits, hasMore := searchSessionFiles(s.sessionsDir, &s.metaCache, "needle", "", 0, searchMaxResults)
	if hasMore {
		t.Fatal("hasMore = true for a small result set")
	}
	if len(hits) != 2 {
		t.Fatalf("hits = %d (%+v), want 2 (system preamble must be skipped)", len(hits), hits)
	}
	if hits[0].Role != "user" || hits[0].MessageID != "m1" {
		t.Fatalf("first hit = %+v", hits[0])
	}
	if hits[1].Role != "assistant" || hits[1].MessageID != "m2" {
		t.Fatalf("second hit = %+v", hits[1])
	}
	for _, hit := range hits {
		if hit.Path != path || hit.SessionID != "sess-a" {
			t.Fatalf("hit metadata = %+v", hit)
		}
		if !strings.Contains(strings.ToLower(hit.Snippet), "needle") {
			t.Fatalf("snippet lost the match: %q", hit.Snippet)
		}
		if hit.At == 0 {
			t.Fatalf("timestamp not parsed: %+v", hit)
		}
	}
}

func TestSearchSessionFilesHonorsOnlyPath(t *testing.T) {
	s := newTestSession(t)
	first := writeSearchSession(t, s.sessionsDir, "p", "a.jsonl", []string{
		sessionHeader(t, "sess-a"),
		sessionLine(t, "user", "a1", "alpha target"),
	})
	writeSearchSession(t, s.sessionsDir, "p", "b.jsonl", []string{
		sessionHeader(t, "sess-b"),
		sessionLine(t, "user", "b1", "beta target"),
	})
	hits, _ := searchSessionFiles(s.sessionsDir, &s.metaCache, "target", first, 0, searchMaxResults)
	if len(hits) != 1 || hits[0].MessageID != "a1" {
		t.Fatalf("hits = %+v, want only the scoped session", hits)
	}
}

func TestSearchSessionFilesSearchableAfterThinkingBlock(t *testing.T) {
	s := newTestSession(t)
	payload := map[string]any{
		"type": "message", "id": "m1", "timestamp": "2026-11-05T10:00:00.000Z",
		"message": map[string]any{
			"role": "assistant",
			"content": []map[string]any{
				{"type": "thinking", "thinking": "hmm"},
				{"type": "text", "text": "final answer with keyword"},
			},
		},
	}
	encoded, _ := json.Marshal(payload)
	writeSearchSession(t, s.sessionsDir, "p", "a.jsonl", []string{sessionHeader(t, "s"), string(encoded)})
	hits, _ := searchSessionFiles(s.sessionsDir, &s.metaCache, "keyword", "", 0, searchMaxResults)
	if len(hits) != 1 || !strings.Contains(hits[0].Snippet, "keyword") {
		t.Fatalf("hits = %+v", hits)
	}
}

func TestSearchSessionFilesMatchesSessionTitle(t *testing.T) {
	s := newTestSession(t)
	writeSearchSession(t, s.sessionsDir, "p", "a.jsonl", []string{
		sessionHeader(t, "s1"),
		`{"type":"session_info","id":"i1","name":"图片讨论"}`,
		sessionLine(t, "user", "m1", "unrelated text"),
	})
	hits, _ := searchSessionFiles(s.sessionsDir, &s.metaCache, "图片", "", 0, searchMaxResults)
	if len(hits) != 1 {
		t.Fatalf("hits = %+v, want the title hit only", hits)
	}
	if hits[0].Kind != "title" || hits[0].Snippet != "图片讨论" || hits[0].MessageID != "" {
		t.Fatalf("title hit = %+v", hits[0])
	}
}

func TestSearchSessionFilesTitleBeforeMessages(t *testing.T) {
	s := newTestSession(t)
	writeSearchSession(t, s.sessionsDir, "p", "a.jsonl", []string{
		sessionHeader(t, "s1"),
		`{"type":"session_info","id":"i1","name":"图片讨论"}`,
		sessionLine(t, "user", "m1", "这条消息也提到图片"),
	})
	hits, _ := searchSessionFiles(s.sessionsDir, &s.metaCache, "图片", "", 0, searchMaxResults)
	if len(hits) != 2 {
		t.Fatalf("hits = %d (%+v)", len(hits), hits)
	}
	if hits[0].Kind != "title" || hits[1].Kind != "message" {
		t.Fatalf("order = %q, %q", hits[0].Kind, hits[1].Kind)
	}
}

func TestSnippetAroundKeepsCJKWhole(t *testing.T) {
	text := strings.Repeat("前", 100) + "关键词" + strings.Repeat("后", 100)
	snippet, ok := snippetAround(text, "关键词", 10)
	if !ok {
		t.Fatal("no match")
	}
	if !strings.Contains(snippet, "关键词") {
		t.Fatalf("snippet = %q", snippet)
	}
	if !strings.HasPrefix(snippet, "…") || !strings.HasSuffix(snippet, "…") {
		t.Fatalf("snippet lacks ellipses: %q", snippet)
	}
	if len([]rune(snippet)) > 10+3+10+2 {
		t.Fatalf("snippet too long: %d runes", len([]rune(snippet)))
	}
}

func TestContainsFoldIgnoresASCIICaseOnly(t *testing.T) {
	if !containsFold([]byte(`{"text":"Hello World"}`), []byte("hello wor")) {
		t.Fatal("ASCII case folding failed")
	}
	if containsFold([]byte(`{"text":"日本語"}`), []byte("テスト")) {
		t.Fatal("false positive")
	}
}

func TestSearchSessionFilesRespectsResultCap(t *testing.T) {
	s := newTestSession(t)
	var lines []string
	lines = append(lines, sessionHeader(t, "s"))
	for i := 0; i < searchMaxResults+10; i++ {
		lines = append(lines, sessionLine(t, "user", fmt.Sprintf("m%d", i), "repeat token"))
	}
	writeSearchSession(t, s.sessionsDir, "p", "a.jsonl", lines)
	hits, hasMore := searchSessionFiles(s.sessionsDir, &s.metaCache, "token", "", 0, searchMaxResults)
	if len(hits) != searchMaxResults {
		t.Fatalf("hits = %d, want cap %d", len(hits), searchMaxResults)
	}
	if !hasMore {
		t.Fatal("hasMore = false with more matches behind the page")
	}

	// Paging must walk the same order without repeating or dropping hits.
	firstPage, more := searchSessionFiles(s.sessionsDir, &s.metaCache, "token", "", 0, 5)
	if !more || len(firstPage) != 5 {
		t.Fatalf("first page = %d hits, more=%v", len(firstPage), more)
	}
	secondPage, _ := searchSessionFiles(s.sessionsDir, &s.metaCache, "token", "", 5, 5)
	if len(secondPage) == 0 {
		t.Fatal("second page is empty")
	}
	for _, a := range firstPage {
		for _, b := range secondPage {
			if a.MessageID == b.MessageID {
				t.Fatalf("hit repeated across pages: %s", a.MessageID)
			}
		}
	}
}
