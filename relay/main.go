// pig-relay bridges the Android app to a `pig --mode rpc` process.
//
// Wire format: one JSON object per line over loopback TCP. Frames produced by
// pig are forwarded verbatim. Relay control frames use the "relay_" type prefix.
package main

import (
	"bufio"
	"bytes"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

const protocol = "relay/1"

// hadClient records whether any connection ever authenticated. A relay that
// has never had a client may be a leftover from an older app install whose
// token no longer matches; it exits instead of blocking the port.
var hadClient atomic.Bool

type frame map[string]any

type connWriter struct {
	mu   sync.Mutex
	conn net.Conn
}

func (w *connWriter) send(v any) error {
	payload, err := json.Marshal(v)
	if err != nil {
		return err
	}
	w.mu.Lock()
	defer w.mu.Unlock()
	_, err = w.conn.Write(append(payload, '\n'))
	return err
}

type pigProcess struct {
	cmd   *exec.Cmd
	stdin io.WriteCloser
	mu    sync.Mutex
	dead  chan struct{}
}

func (p *pigProcess) write(line []byte) error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.stdin == nil {
		return errors.New("pig stdin closed")
	}
	if _, err := p.stdin.Write(append(line, '\n')); err != nil {
		return err
	}
	return nil
}

func (p *pigProcess) stop() {
	p.mu.Lock()
	if p.stdin != nil {
		_ = p.stdin.Close()
		p.stdin = nil
	}
	p.mu.Unlock()
	if p.cmd.Process == nil {
		return
	}
	_ = p.cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-p.dead:
	case <-time.After(2 * time.Second):
		_ = p.cmd.Process.Kill()
	}
}

type session struct {
	token       string
	cwd         string
	uiDir       string
	sessionsDir string
	pigBin      string

	mu      sync.Mutex
	conn    net.Conn
	out     *connWriter
	connGen uint64

	pigMu     sync.Mutex
	pig       *pigProcess
	pendingUI map[string]json.RawMessage

	uiMu   sync.Mutex
	uiHash string

	streamMu    sync.Mutex
	streamText  string
	activeTools map[string]toolProgress

	stopOnce sync.Once
	stopped  chan struct{}
}

type toolProgress struct {
	Name    string
	Partial json.RawMessage
}

// newSession builds the long-lived bridge. The pig lives in this struct, not in
// the connection, so detaching the app does not abort a running turn.
func newSession(token, cwd, uiDir, sessionsDir, pigBin string) *session {
	return &session{
		token:       token,
		cwd:         cwd,
		uiDir:       uiDir,
		sessionsDir: sessionsDir,
		pigBin:      pigBin,
		pendingUI:   map[string]json.RawMessage{},
		activeTools: map[string]toolProgress{},
		stopped:     make(chan struct{}),
	}
}

func (s *session) stop() {
	s.stopOnce.Do(func() {
		close(s.stopped)
		s.pigMu.Lock()
		pig := s.pig
		s.pig = nil
		s.pendingUI = map[string]json.RawMessage{}
		s.pigMu.Unlock()
		if pig != nil {
			pig.stop()
		}
		s.mu.Lock()
		conn := s.conn
		s.conn = nil
		s.out = nil
		s.mu.Unlock()
		if conn != nil {
			_ = conn.Close()
		}
	})
}

func (s *session) currentOut() *connWriter {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.out
}

func (s *session) send(v any) error {
	out := s.currentOut()
	if out == nil {
		return nil
	}
	return out.send(v)
}

func (s *session) sendRaw(payload []byte) error {
	out := s.currentOut()
	if out == nil {
		return nil
	}
	return out.sendRaw(payload)
}

func (s *session) currentPig() *pigProcess {
	s.pigMu.Lock()
	defer s.pigMu.Unlock()
	return s.pig
}

// trackPigFrame keeps the minimal state needed to resume a client mid-turn:
// the assistant text streamed so far and the progress of running tools.
func (s *session) trackPigFrame(payload []byte) {
	var head struct {
		Type    string `json:"type"`
		Message struct {
			Role string `json:"role"`
		} `json:"message"`
		AssistantMessageEvent struct {
			Type  string `json:"type"`
			Delta string `json:"delta"`
		} `json:"assistantMessageEvent"`
		ToolCallID    string          `json:"toolCallId"`
		ToolName      string          `json:"toolName"`
		PartialResult json.RawMessage `json:"partialResult"`
	}
	if !bytes.Contains(payload, []byte(`"type":"`)) {
		return
	}
	if err := json.Unmarshal(payload, &head); err != nil {
		return
	}
	switch head.Type {
	case "message_start":
		if head.Message.Role == "assistant" {
			s.streamMu.Lock()
			s.streamText = ""
			s.streamMu.Unlock()
		}
	case "message_update":
		if head.AssistantMessageEvent.Type == "text_delta" && head.AssistantMessageEvent.Delta != "" {
			s.streamMu.Lock()
			s.streamText += head.AssistantMessageEvent.Delta
			s.streamMu.Unlock()
		}
	case "message_end":
		if head.Message.Role == "assistant" {
			s.streamMu.Lock()
			s.streamText = ""
			s.streamMu.Unlock()
		}
	case "tool_execution_start":
		if head.ToolCallID != "" {
			s.streamMu.Lock()
			s.activeTools[head.ToolCallID] = toolProgress{Name: head.ToolName}
			s.streamMu.Unlock()
		}
	case "tool_execution_update":
		if head.ToolCallID != "" && len(head.PartialResult) > 0 {
			s.streamMu.Lock()
			tool := s.activeTools[head.ToolCallID]
			tool.Partial = head.PartialResult
			s.activeTools[head.ToolCallID] = tool
			s.streamMu.Unlock()
		}
	case "tool_execution_end":
		if head.ToolCallID != "" {
			s.streamMu.Lock()
			delete(s.activeTools, head.ToolCallID)
			s.streamMu.Unlock()
		}
	case "agent_end", "agent_settled", "error":
		s.streamMu.Lock()
		s.streamText = ""
		s.activeTools = map[string]toolProgress{}
		s.streamMu.Unlock()
	}
}

// attach makes conn the active client and sends it hello, the current UI pack
// and any extension dialog that is still waiting for an answer. A previous
// client (for example a half-dead socket after a process freeze) is kicked.
func (s *session) attach(conn net.Conn) (*connWriter, uint64, error) {
	s.mu.Lock()
	old := s.conn
	s.connGen++
	gen := s.connGen
	s.conn = conn
	out := &connWriter{conn: conn}
	s.out = out
	s.mu.Unlock()
	if old != nil && old != conn {
		_ = old.Close()
	}

	pig, started, err := s.ensurePig()
	if err != nil {
		return out, gen, err
	}
	_ = out.send(frame{
		"type":     "relay_hello",
		"protocol": protocol,
		"cwd":      s.cwd,
		"uiDir":    s.uiDir,
		"pigPid":   pig.cmd.Process.Pid,
		"resumed":  !started,
	})

	files, assets, assetHashes, hash, err := scanUI(s.uiDir)
	if err == nil {
		s.uiMu.Lock()
		s.uiHash = hash
		s.uiMu.Unlock()
		_ = out.send(frame{
			"type":        "relay_ui_pack",
			"hash":        hash,
			"files":       files,
			"assets":      assets,
			"assetHashes": assetHashes,
		})
	}

	s.pigMu.Lock()
	pending := make([]json.RawMessage, 0, len(s.pendingUI))
	for _, raw := range s.pendingUI {
		pending = append(pending, raw)
	}
	s.pigMu.Unlock()
	for _, raw := range pending {
		_ = out.sendRaw(raw)
	}

	// Replay the in-flight assistant text and running tools so a reconnecting
	// client can pick up the turn without waiting for the next delta.
	s.streamMu.Lock()
	resumeText := s.streamText
	tools := make(map[string]toolProgress, len(s.activeTools))
	for id, tool := range s.activeTools {
		tools[id] = tool
	}
	s.streamMu.Unlock()
	if resumeText != "" {
		_ = out.send(frame{"type": "relay_resume", "text": resumeText})
	}
	for id, tool := range tools {
		_ = out.send(frame{
			"type":          "relay_tool",
			"toolCallId":    id,
			"toolName":      tool.Name,
			"partialResult": tool.Partial,
		})
	}
	return out, gen, nil
}

// detach only drops the client; the pig keeps running so a reconnect can
// resume an in-flight turn.
func (s *session) detach(conn net.Conn, gen uint64) {
	s.mu.Lock()
	if s.conn == conn && s.connGen == gen {
		s.conn = nil
		s.out = nil
	}
	s.mu.Unlock()
	_ = conn.Close()
}

func (s *session) ensurePig() (*pigProcess, bool, error) {
	s.pigMu.Lock()
	defer s.pigMu.Unlock()
	if s.pig != nil {
		return s.pig, false, nil
	}
	pig, err := s.startPigLocked()
	if err != nil {
		return nil, false, err
	}
	s.pig = pig
	s.pendingUI = map[string]json.RawMessage{}
	s.streamMu.Lock()
	s.streamText = ""
	s.activeTools = map[string]toolProgress{}
	s.streamMu.Unlock()
	return pig, true, nil
}

// startPigLocked expects pigMu to be held.
func (s *session) startPigLocked() (*pigProcess, error) {
	cmd := exec.Command(s.pigBin, "--mode", "rpc")
	cmd.Dir = s.cwd
	cmd.Env = os.Environ()

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, err
	}

	pig := &pigProcess{cmd: cmd, stdin: stdin, dead: make(chan struct{})}

	go func() {
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 64*1024), 32*1024*1024)
		for scanner.Scan() {
			line := scanner.Bytes()
			if len(line) == 0 {
				continue
			}
			payload := append([]byte(nil), line...)
			s.trackPigFrame(payload)
			// Keep extension dialogs that have not been answered yet, so a
			// client that reconnects can answer them.
			if bytes.Contains(payload, []byte(`"extension_ui_request"`)) {
				var env struct {
					Type   string `json:"type"`
					ID     string `json:"id"`
					Method string `json:"method"`
				}
				answerable := env.Method == "confirm" || env.Method == "select" || env.Method == "input" || env.Method == "editor"
				if json.Unmarshal(payload, &env) == nil && env.Type == "extension_ui_request" && env.ID != "" && answerable {
					s.pigMu.Lock()
					if s.pendingUI == nil {
						s.pendingUI = map[string]json.RawMessage{}
					}
					s.pendingUI[env.ID] = payload
					s.pigMu.Unlock()
				}
			}
			_ = s.sendRaw(payload)
		}
	}()

	go func() {
		scanner := bufio.NewScanner(stderr)
		scanner.Buffer(make([]byte, 16*1024), 4*1024*1024)
		for scanner.Scan() {
			line := scanner.Text()
			if line == "" {
				continue
			}
			_ = s.send(frame{"type": "relay_log", "message": line})
		}
	}()

	go func() {
		err := cmd.Wait()
		close(pig.dead)
		code := 0
		if err != nil {
			var exitErr *exec.ExitError
			if errors.As(err, &exitErr) {
				code = exitErr.ExitCode()
			} else {
				code = -1
			}
		}
		s.pigMu.Lock()
		if s.pig == pig {
			s.pig = nil
			s.pendingUI = map[string]json.RawMessage{}
		}
		s.pigMu.Unlock()
		s.streamMu.Lock()
		s.streamText = ""
		s.activeTools = map[string]toolProgress{}
		s.streamMu.Unlock()
		_ = s.send(frame{"type": "relay_exit", "code": code})
	}()

	return pig, nil
}

// sendRaw forwards a pig frame unchanged.
func (w *connWriter) sendRaw(payload []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	_, err := w.conn.Write(append(payload, '\n'))
	return err
}

func (s *session) restartPig() error {
	s.pigMu.Lock()
	old := s.pig
	s.pig = nil
	s.pendingUI = map[string]json.RawMessage{}
	s.pigMu.Unlock()
	if old != nil {
		old.stop()
	}
	pig, _, err := s.ensurePig()
	if err != nil {
		_ = s.send(frame{"type": "relay_error", "message": "cannot start pig: " + err.Error()})
		return err
	}
	_ = s.send(frame{"type": "relay_restarted", "pid": pig.cmd.Process.Pid})
	return nil
}

func (s *session) handleClientLine(line []byte) {
	var head struct {
		Type  string `json:"type"`
		ID    any    `json:"id"`
		Token string `json:"token"`
		Root  string `json:"root"`
		Path  string `json:"path"`
		Name  string `json:"name"`
	}
	if err := json.Unmarshal(line, &head); err != nil {
		_ = s.send(frame{"type": "relay_error", "message": "invalid json: " + err.Error()})
		return
	}

	if !strings.HasPrefix(head.Type, "relay_") {
		pig := s.currentPig()
		if pig == nil {
			_ = s.send(frame{"type": "relay_error", "message": "pig is not running"})
			return
		}
		if err := pig.write(line); err != nil {
			_ = s.send(frame{"type": "relay_error", "message": "pig write failed: " + err.Error()})
		}
		if head.Type == "extension_ui_response" {
			var response struct {
				ID string `json:"id"`
			}
			if json.Unmarshal(line, &response) == nil && response.ID != "" {
				s.pigMu.Lock()
				delete(s.pendingUI, response.ID)
				s.pigMu.Unlock()
			}
		}
		return
	}

	switch head.Type {
	case "relay_auth":
		// Already authenticated before this loop.
	case "relay_ping":
		_ = s.send(frame{"type": "relay_pong", "id": head.ID})
	case "relay_restart":
		_ = s.restartPig()
	case "relay_projects":
		root := head.Root
		if root == "" {
			root = s.cwd
		}
		_ = s.send(frame{"type": "relay_projects", "id": head.ID, "root": root, "projects": listProjects(root)})
	case "relay_sessions":
		_ = s.send(frame{"type": "relay_sessions", "id": head.ID, "sessions": listSessions(s.sessionsDir)})
	case "relay_session_rename":
		if err := appendSessionInfo(head.Path, head.Name, s.sessionsDir); err != nil {
			_ = s.send(frame{"type": "relay_session_error", "id": head.ID, "message": "rename: " + err.Error()})
		} else {
			_ = s.send(frame{
				"type": "relay_session_renamed",
				"id":   head.ID,
				"path": head.Path,
				"name": truncateRunes(head.Name, 80),
			})
		}
	case "relay_session_delete":
		if err := deleteSession(head.Path, s.sessionsDir); err != nil {
			_ = s.send(frame{"type": "relay_session_error", "id": head.ID, "message": "delete: " + err.Error()})
		} else {
			_ = s.send(frame{"type": "relay_session_deleted", "id": head.ID, "path": head.Path})
		}
	case "relay_ui":
		files, assets, assetHashes, hash, err := scanUI(s.uiDir)
		if err != nil {
			_ = s.send(frame{"type": "relay_error", "message": "ui pack: " + err.Error()})
			return
		}
		_ = s.send(frame{
			"type":        "relay_ui_pack",
			"hash":        hash,
			"files":       files,
			"assets":      assets,
			"assetHashes": assetHashes,
		})
	case "relay_stop":
		s.mu.Lock()
		conn := s.conn
		s.mu.Unlock()
		if conn != nil {
			_ = conn.Close()
		}
	default:
		_ = s.send(frame{"type": "relay_error", "message": "unknown relay frame: " + head.Type})
	}
}

func (s *session) watchUI() {
	for {
		select {
		case <-s.stopped:
			return
		case <-time.After(800 * time.Millisecond):
		}
		files, assets, assetHashes, hash, err := scanUI(s.uiDir)
		if err != nil {
			continue
		}
		s.uiMu.Lock()
		if hash == s.uiHash {
			s.uiMu.Unlock()
			continue
		}
		s.uiHash = hash
		s.uiMu.Unlock()
		_ = s.send(frame{
			"type":        "relay_ui_pack",
			"hash":        hash,
			"files":       files,
			"assets":      assets,
			"assetHashes": assetHashes,
		})
	}
}

type sessionFile struct {
	path string
	info os.FileInfo
}

// listSessions returns the most recently updated Session files, newest first.
func listSessions(dir string) []map[string]any {
	var files []sessionFile
	projects, err := os.ReadDir(dir)
	if err != nil {
		return []map[string]any{}
	}
	for _, project := range projects {
		if !project.IsDir() {
			continue
		}
		entries, err := os.ReadDir(filepath.Join(dir, project.Name()))
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
				continue
			}
			info, err := entry.Info()
			if err != nil {
				continue
			}
			files = append(files, sessionFile{filepath.Join(dir, project.Name(), entry.Name()), info})
		}
	}
	sort.Slice(files, func(i, j int) bool {
		return files[i].info.ModTime().After(files[j].info.ModTime())
	})
	if len(files) > 60 {
		files = files[:60]
	}
	out := make([]map[string]any, 0, len(files))
	for _, file := range files {
		out = append(out, readSessionMeta(file.path, file.info))
	}
	return out
}

func readSessionMeta(path string, info os.FileInfo) map[string]any {
	meta := map[string]any{
		"path":    path,
		"updated": info.ModTime().UnixMilli(),
		"size":    info.Size(),
		"id":      "",
		"cwd":     "",
		"name":    "",
		"preview": "",
	}
	file, err := os.Open(path)
	if err != nil {
		return meta
	}
	defer file.Close()

	reader := bufio.NewReaderSize(io.LimitReader(file, 256*1024), 64*1024)
	line, err := reader.ReadBytes('\n')
	if err == nil || len(line) > 0 {
		var header struct {
			ID  string `json:"id"`
			CWD string `json:"cwd"`
		}
		if json.Unmarshal(line, &header) == nil {
			meta["id"] = header.ID
			meta["cwd"] = header.CWD
		}
	}

	scanner := bufio.NewScanner(reader)
	scanner.Buffer(make([]byte, 16*1024), 512*1024)
	name, preview := "", ""
	for scanner.Scan() {
		var entry struct {
			Type    string `json:"type"`
			Name    string `json:"name"`
			Message struct {
				Role    string `json:"role"`
				Content []struct {
					Type string `json:"type"`
					Text string `json:"text"`
				} `json:"content"`
			} `json:"message"`
		}
		if json.Unmarshal(scanner.Bytes(), &entry) != nil {
			continue
		}
		if entry.Type == "session_info" {
			name = entry.Name
		}
		if preview == "" && entry.Message.Role == "user" {
			for _, block := range entry.Message.Content {
				if block.Type == "text" && strings.TrimSpace(block.Text) != "" {
					preview = truncateRunes(block.Text, 80)
					break
				}
			}
		}
		if name != "" && preview != "" {
			break
		}
	}
	meta["name"] = name
	meta["preview"] = preview
	// A rename is appended to the end; the latest session_info wins. Check the
	// tail as well so renames in a large file are picked up.
	if tailName, ok := tailSessionName(path, info.Size()); ok {
		meta["name"] = tailName
	}
	return meta
}

// tailSessionName returns the last session_info name in the final 256 KiB.
func tailSessionName(path string, size int64) (string, bool) {
	const tail = 256 * 1024
	if size <= 0 {
		return "", false
	}
	n := int64(tail)
	if n > size {
		n = size
	}
	f, err := os.Open(path)
	if err != nil {
		return "", false
	}
	defer f.Close()
	buf := make([]byte, n)
	if _, err := f.ReadAt(buf, size-n); err != nil && err != io.EOF {
		return "", false
	}
	text := string(buf)
	if size > n {
		// Discard the partial first line.
		idx := strings.IndexByte(text, '\n')
		if idx < 0 {
			return "", false
		}
		text = text[idx+1:]
	}
	name, found := "", false
	for _, line := range strings.Split(text, "\n") {
		var entry struct {
			Type string `json:"type"`
			Name string `json:"name"`
		}
		if json.Unmarshal([]byte(line), &entry) == nil && entry.Type == "session_info" {
			name, found = entry.Name, true
		}
	}
	return name, found
}

func validateSessionPath(path, dir string) error {
	if strings.TrimSpace(path) == "" {
		return errors.New("missing session path")
	}
	abs, err := filepath.Abs(path)
	if err != nil {
		return err
	}
	root, err := filepath.Abs(dir)
	if err != nil {
		return err
	}
	rel, err := filepath.Rel(root, abs)
	if err != nil || rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return errors.New("session path is outside the sessions directory")
	}
	if !strings.HasSuffix(abs, ".jsonl") {
		return errors.New("not a session file")
	}
	return nil
}

// appendSessionInfo appends a session_info entry (display name) to a session
// file. The latest such entry wins, so this is how the manager renames sessions.
func appendSessionInfo(path, name, dir string) error {
	if err := validateSessionPath(path, dir); err != nil {
		return err
	}
	name = truncateRunes(name, 80)
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND, 0)
	if err != nil {
		return err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return err
	}

	needNewline := true
	var parent any
	if size := info.Size(); size > 0 {
		n := int64(64 * 1024)
		if n > size {
			n = size
		}
		buf := make([]byte, n)
		if _, err := f.ReadAt(buf, size-n); err != nil && err != io.EOF {
			return err
		}
		needNewline = !bytes.HasSuffix(buf, []byte("\n"))
		text := strings.TrimRight(string(buf), "\r\n")
		if idx := strings.LastIndexByte(text, '\n'); idx >= 0 {
			text = text[idx+1:]
		}
		var last struct {
			ID string `json:"id"`
		}
		if json.Unmarshal([]byte(text), &last) == nil && last.ID != "" {
			parent = last.ID
		}
	}

	rawID := make([]byte, 4)
	_, _ = rand.Read(rawID)
	entry := map[string]any{
		"type":      "session_info",
		"id":        hex.EncodeToString(rawID),
		"parentId":  parent,
		"timestamp": time.Now().UTC().Format("2006-01-02T15:04:05.000Z"),
		"name":      name,
	}
	data, err := json.Marshal(entry)
	if err != nil {
		return err
	}
	if needNewline {
		data = append([]byte("\n"), data...)
	}
	_, err = f.Write(append(data, '\n'))
	if err != nil {
		return err
	}
	// Metadata changes should not reorder the history list.
	_ = os.Chtimes(path, info.ModTime(), info.ModTime())
	return nil
}

func deleteSession(path, dir string) error {
	if err := validateSessionPath(path, dir); err != nil {
		return err
	}
	return os.Remove(path)
}

func truncateRunes(text string, limit int) string {
	text = strings.ReplaceAll(strings.TrimSpace(text), "\n", " ")
	runes := []rune(text)
	if len(runes) <= limit {
		return text
	}
	return string(runes[:limit]) + "…"
}

func listProjects(root string) []map[string]string {
	entries, err := os.ReadDir(root)
	if err != nil {
		return nil
	}
	out := make([]map[string]string, 0, len(entries))
	for _, entry := range entries {
		if !entry.IsDir() || strings.HasPrefix(entry.Name(), ".") {
			continue
		}
		out = append(out, map[string]string{
			"name": entry.Name(),
			"path": filepath.Join(root, entry.Name()),
		})
	}
	sort.Slice(out, func(i, j int) bool { return out[i]["name"] < out[j]["name"] })
	if len(out) > 200 {
		out = out[:200]
	}
	return out
}

// scanUI reads a UI pack directory into {relative path: text content}.
func scanUI(dir string) (map[string]string, map[string]string, map[string]string, string, error) {
	files := map[string]string{}
	assets := map[string]string{}
	assetHashes := map[string]string{}
	if dir == "" {
		return files, assets, assetHashes, "", nil
	}
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return files, assets, assetHashes, "", nil
	}

	type entry struct {
		rel   string
		data  []byte
		asset bool
	}
	var entries []entry

	err = filepath.WalkDir(dir, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		name := d.Name()
		if d.IsDir() {
			if path != dir && strings.HasPrefix(name, ".") {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.HasPrefix(name, ".") || d.Type()&os.ModeSymlink != 0 {
			return nil
		}
		info, err := d.Info()
		if err != nil || info.Size() > 512*1024 {
			return nil
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return nil
		}
		rel = filepath.ToSlash(rel)
		asset := strings.HasPrefix(rel, "assets/")
		if !asset && isBinary(data) {
			return nil
		}
		entries = append(entries, entry{rel: rel, data: data, asset: asset})
		return nil
	})
	if err != nil {
		return files, assets, assetHashes, "", err
	}

	sort.Slice(entries, func(i, j int) bool { return entries[i].rel < entries[j].rel })
	h := sha256.New()
	for _, e := range entries {
		if e.asset {
			sum := sha256.Sum256(e.data)
			assetHashes[e.rel] = hex.EncodeToString(sum[:])
			assets[e.rel] = base64.StdEncoding.EncodeToString(e.data)
		} else {
			files[e.rel] = string(e.data)
		}
		h.Write([]byte(e.rel))
		h.Write([]byte{0})
		h.Write(e.data)
		h.Write([]byte{0})
	}
	return files, assets, assetHashes, hex.EncodeToString(h.Sum(nil)), nil
}

func isBinary(data []byte) bool {
	limit := len(data)
	if limit > 8192 {
		limit = 8192
	}
	for i := 0; i < limit; i++ {
		if data[i] == 0 {
			return true
		}
	}
	return false
}

func authenticate(conn net.Conn, token string) (*bufio.Reader, error) {
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	reader := bufio.NewReaderSize(conn, 256*1024)
	line, err := reader.ReadBytes('\n')
	if err != nil {
		return reader, err
	}
	_ = conn.SetReadDeadline(time.Time{})

	var auth struct {
		Type  string `json:"type"`
		Token string `json:"token"`
	}
	if err := json.Unmarshal(line, &auth); err != nil {
		return reader, fmt.Errorf("auth frame: %w", err)
	}
	if auth.Type != "relay_auth" {
		return reader, errors.New("first frame must be relay_auth")
	}
	if subtle.ConstantTimeCompare([]byte(auth.Token), []byte(token)) != 1 {
		return reader, errors.New("bad token")
	}
	return reader, nil
}

func main() {
	var (
		listen   = flag.String("listen", "127.0.0.1", "listen address")
		port     = flag.Int("port", 8791, "listen port")
		token    = flag.String("token", "", "shared token (or use --token-file)")
		tokenF   = flag.String("token-file", "", "file containing the shared token")
		cwd      = flag.String("cwd", "", "working directory for pig (default: $HOME)")
		uiDir    = flag.String("ui-dir", "", "UI pack directory (default: ~/.pig/android-ui)")
		pigBin   = flag.String("pig", "pig", "pig binary")
		readOnly = flag.Bool("version", false, "print version and exit")
	)
	flag.Parse()

	if *readOnly {
		fmt.Println(protocol)
		return
	}
	if *token == "" && *tokenF != "" {
		data, err := os.ReadFile(*tokenF)
		if err != nil {
			log.Fatalf("read token file: %v", err)
		}
		*token = strings.TrimSpace(string(data))
	}
	if *token == "" {
		buf := make([]byte, 16)
		if _, err := rand.Read(buf); err != nil {
			log.Fatalf("generate token: %v", err)
		}
		*token = hex.EncodeToString(buf)
		log.Printf("generated token: %s", *token)
	}
	if *cwd == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			log.Fatalf("resolve home: %v", err)
		}
		*cwd = home
	}
	if *uiDir == "" {
		*uiDir = filepath.Join(*cwd, ".pig", "android-ui")
	}
	agentDir := os.Getenv("PIG_CODING_AGENT_DIR")
	if agentDir == "" {
		agentDir = filepath.Join(*cwd, ".pig", "agent")
	}
	sessionsDir := filepath.Join(agentDir, "sessions")

	listener, err := net.Listen("tcp", fmt.Sprintf("%s:%d", *listen, *port))
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	log.Printf("pig-relay %s on %s (cwd=%s ui=%s pig=%s)", protocol, listener.Addr(), *cwd, *uiDir, *pigBin)

	agent := newSession(*token, *cwd, *uiDir, sessionsDir, *pigBin)
	go agent.watchUI()

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		agent.stop()
		_ = listener.Close()
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		go serve(conn, agent)
	}
}

func serve(conn net.Conn, s *session) {
	defer conn.Close()

	reader, err := authenticate(conn, s.token)
	if err != nil {
		_ = (&connWriter{conn: conn}).send(frame{"type": "relay_error", "message": "auth failed: " + err.Error()})
		if !hadClient.Load() {
			go func() {
				time.Sleep(300 * time.Millisecond)
				log.Printf("no client ever authenticated; releasing port")
				os.Exit(0)
			}()
		}
		return
	}
	hadClient.Store(true)

	_, gen, err := s.attach(conn)
	if err != nil {
		_ = (&connWriter{conn: conn}).send(frame{"type": "relay_error", "message": "cannot start pig: " + err.Error()})
		s.detach(conn, gen)
		return
	}
	defer s.detach(conn, gen)

	for {
		line, err := reader.ReadBytes('\n')
		if len(line) > 0 {
			trimmed := []byte(strings.TrimSpace(string(line)))
			if len(trimmed) > 0 {
				s.handleClientLine(trimmed)
			}
		}
		if err != nil {
			return
		}
	}
}
