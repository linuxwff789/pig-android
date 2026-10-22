package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"net"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"
)

// agentProc is one pig process pinned to one session. Sessions with a running
// turn keep their process (parked); idle agents are stopped when they are not
// the active session so history sessions need no process.
type agentProc struct {
	pig  *pigProcess
	path string

	mu          sync.Mutex
	streamText  string
	activeTools map[string]toolProgress
	pendingUI   map[string]json.RawMessage
	busy        bool
}

type toolProgress struct {
	Name    string
	Partial json.RawMessage
}

type msgCacheEntry struct {
	size     int64
	mod      time.Time
	messages []json.RawMessage
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

	startMu sync.Mutex
	agentMu sync.Mutex
	agents  map[string]*agentProc
	active  *agentProc
	// activePath is the session the app is looking at; it may have no agent.
	activePath string

	uiMu   sync.Mutex
	uiHash string

	msgMu    sync.Mutex
	msgCache map[string]msgCacheEntry

	stopOnce sync.Once
	stopped  chan struct{}
}

func newSession(token, cwd, uiDir, sessionsDir, pigBin string) *session {
	return &session{
		token:       token,
		cwd:         cwd,
		uiDir:       uiDir,
		sessionsDir: sessionsDir,
		pigBin:      pigBin,
		agents:      map[string]*agentProc{},
		msgCache:    map[string]msgCacheEntry{},
		stopped:     make(chan struct{}),
	}
}

func (s *session) stop() {
	s.stopOnce.Do(func() {
		close(s.stopped)
		s.agentMu.Lock()
		agents := make([]*agentProc, 0, len(s.agents))
		for _, a := range s.agents {
			agents = append(agents, a)
		}
		s.agents = map[string]*agentProc{}
		s.active = nil
		s.agentMu.Unlock()
		for _, a := range agents {
			a.pig.stop()
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

func (s *session) activeAgent() *agentProc {
	s.agentMu.Lock()
	defer s.agentMu.Unlock()
	return s.active
}

func (s *session) isActive(a *agentProc) bool {
	s.agentMu.Lock()
	defer s.agentMu.Unlock()
	return s.active == a
}

func (a *agentProc) isBusy() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.busy
}

func (a *agentProc) clearPending(id string) {
	a.mu.Lock()
	delete(a.pendingUI, id)
	a.mu.Unlock()
}

// trackFrame keeps per-agent state and learns its session path from get_state.
func (a *agentProc) trackFrame(s *session, payload []byte) {
	var head struct {
		Type    string `json:"type"`
		Command string `json:"command"`
		Data    struct {
			SessionFile string `json:"sessionFile"`
		} `json:"data"`
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
		ID            string          `json:"id"`
		Method        string          `json:"method"`
	}
	if !bytes.Contains(payload, []byte(`"type"`)) {
		return
	}
	if err := json.Unmarshal(payload, &head); err != nil {
		return
	}
	switch head.Type {
	case "response":
		if head.Command == "get_state" && head.Data.SessionFile != "" {
			s.mapAgentPath(a, head.Data.SessionFile)
		}
	case "agent_start":
		a.mu.Lock()
		a.busy = true
		a.mu.Unlock()
	case "agent_end", "agent_settled", "error":
		a.mu.Lock()
		a.busy = false
		a.streamText = ""
		a.activeTools = map[string]toolProgress{}
		a.mu.Unlock()
		if !s.isActive(a) {
			go s.reapAgent(a)
		}
	case "message_start":
		if head.Message.Role == "assistant" {
			a.mu.Lock()
			a.streamText = ""
			a.mu.Unlock()
		}
	case "message_update":
		if head.AssistantMessageEvent.Type == "text_delta" && head.AssistantMessageEvent.Delta != "" {
			a.mu.Lock()
			a.streamText += head.AssistantMessageEvent.Delta
			a.mu.Unlock()
		}
	case "message_end":
		if head.Message.Role == "assistant" {
			a.mu.Lock()
			a.streamText = ""
			a.mu.Unlock()
		}
	case "tool_execution_start":
		if head.ToolCallID != "" {
			a.mu.Lock()
			a.activeTools[head.ToolCallID] = toolProgress{Name: head.ToolName}
			a.mu.Unlock()
		}
	case "tool_execution_update":
		if head.ToolCallID != "" && len(head.PartialResult) > 0 {
			a.mu.Lock()
			tool := a.activeTools[head.ToolCallID]
			tool.Partial = head.PartialResult
			a.activeTools[head.ToolCallID] = tool
			a.mu.Unlock()
		}
	case "tool_execution_end":
		if head.ToolCallID != "" {
			a.mu.Lock()
			delete(a.activeTools, head.ToolCallID)
			a.mu.Unlock()
		}
	case "extension_ui_request":
		answerable := head.Method == "confirm" || head.Method == "select" ||
			head.Method == "input" || head.Method == "editor"
		if head.ID != "" && answerable {
			a.mu.Lock()
			a.pendingUI[head.ID] = append([]byte(nil), payload...)
			a.mu.Unlock()
		}
	}
}

// tagResponse adds the producing session path to state/message responses so a
// client can drop stale replies that raced a session switch.
func (s *session) tagResponse(a *agentProc, payload []byte) []byte {
	head := payload
	if len(head) > 256 {
		head = head[:256]
	}
	if !bytes.Contains(head, []byte(`"command":"get_messages"`)) &&
		!bytes.Contains(head, []byte(`"command":"get_state"`)) &&
		!bytes.Contains(head, []byte(`"command":"get_session_stats"`)) {
		return payload
	}
	s.agentMu.Lock()
	path := a.path
	s.agentMu.Unlock()
	if path == "" || len(payload) == 0 || payload[0] != '{' {
		return payload
	}
	quoted, err := json.Marshal(path)
	if err != nil {
		return payload
	}
	out := make([]byte, 0, len(payload)+len(quoted)+20)
	out = append(out, '{')
	out = append(out, []byte(`"sessionPath":`)...)
	out = append(out, quoted...)
	out = append(out, ',')
	out = append(out, payload[1:]...)
	return out
}

func (s *session) mapAgentPath(a *agentProc, path string) {
	if path == "" {
		return
	}
	s.agentMu.Lock()
	old := a.path
	if old != "" && old != path && s.agents[old] == a {
		delete(s.agents, old)
	}
	if s.agents[""] == a {
		delete(s.agents, "")
	}
	a.path = path
	s.agents[path] = a
	if s.active == a {
		s.activePath = path
	}
	s.agentMu.Unlock()
}

// startAgent launches pig with --session (when path is set) so no switch is
// needed for history sessions.
func (s *session) startAgent(path string, load bool) (*agentProc, error) {
	args := []string{"--mode", "rpc"}
	if load && path != "" {
		args = append(args, "--session", path)
	}
	cmd := exec.Command(s.pigBin, args...)
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
	a := &agentProc{
		pig:         pig,
		path:        path,
		activeTools: map[string]toolProgress{},
		pendingUI:   map[string]json.RawMessage{},
	}

	go func() {
		scanner := bufio.NewScanner(stdout)
		scanner.Buffer(make([]byte, 64*1024), 32*1024*1024)
		for scanner.Scan() {
			line := scanner.Bytes()
			if len(line) == 0 {
				continue
			}
			payload := append([]byte(nil), line...)
			a.trackFrame(s, payload)
			if s.isActive(a) {
				_ = s.sendRaw(s.tagResponse(a, payload))
			}
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
			if s.isActive(a) {
				_ = s.send(frame{"type": "relay_log", "message": line})
			}
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
		s.agentMu.Lock()
		if a.path != "" && s.agents[a.path] == a {
			delete(s.agents, a.path)
		}
		if s.agents[""] == a {
			delete(s.agents, "")
		}
		wasActive := s.active == a
		if wasActive {
			s.active = nil
		}
		s.agentMu.Unlock()
		if wasActive {
			_ = s.send(frame{"type": "relay_exit", "code": code})
		}
	}()

	return a, nil
}

func (s *session) ensureAgent(path string, load bool) (*agentProc, error) {
	s.agentMu.Lock()
	if path != "" {
		if a := s.agents[path]; a != nil {
			s.active = a
			s.activePath = path
			s.agentMu.Unlock()
			return a, nil
		}
	}
	s.agentMu.Unlock()

	s.startMu.Lock()
	defer s.startMu.Unlock()
	s.agentMu.Lock()
	if path != "" {
		if a := s.agents[path]; a != nil {
			s.active = a
			s.activePath = path
			s.agentMu.Unlock()
			return a, nil
		}
	}
	s.agentMu.Unlock()

	a, err := s.startAgent(path, load)
	if err != nil {
		return nil, err
	}
	s.agentMu.Lock()
	if path == "" {
		if old := s.agents[""]; old != nil && old != a {
			delete(s.agents, "")
			go old.pig.stop()
		}
	}
	s.agents[path] = a
	s.active = a
	s.activePath = path
	s.agentMu.Unlock()
	return a, nil
}

func (s *session) stopAgent(a *agentProc) {
	if a == nil {
		return
	}
	s.agentMu.Lock()
	if a.path != "" && s.agents[a.path] == a {
		delete(s.agents, a.path)
	}
	if s.agents[""] == a {
		delete(s.agents, "")
	}
	if s.active == a {
		s.active = nil
	}
	s.agentMu.Unlock()
	a.pig.stop()
}

func (s *session) reapAgent(a *agentProc) {
	time.Sleep(1500 * time.Millisecond)
	if s.isActive(a) || a.isBusy() {
		return
	}
	s.stopAgent(a)
}

// switchTo makes path active, reusing a parked agent when it exists. Idle
// agents of the previous session are stopped; history sessions stay read-only.
func (s *session) switchTo(path string) *agentProc {
	s.agentMu.Lock()
	old := s.active
	a := s.agents[path]
	s.active = a
	s.activePath = path
	s.agentMu.Unlock()
	if old != nil && old != a && !old.isBusy() {
		s.stopAgent(old)
	}
	return a
}

func (s *session) replayAgent(a *agentProc) {
	if a == nil {
		return
	}
	a.mu.Lock()
	pending := make([]json.RawMessage, 0, len(a.pendingUI))
	for _, raw := range a.pendingUI {
		pending = append(pending, raw)
	}
	resume := a.streamText
	tools := make(map[string]toolProgress, len(a.activeTools))
	for id, tool := range a.activeTools {
		tools[id] = tool
	}
	a.mu.Unlock()

	for _, raw := range pending {
		_ = s.sendRaw(raw)
	}
	if resume != "" {
		_ = s.send(frame{"type": "relay_resume", "text": resume})
	}
	for id, tool := range tools {
		_ = s.send(frame{
			"type":          "relay_tool",
			"toolCallId":    id,
			"toolName":      tool.Name,
			"partialResult": tool.Partial,
		})
	}
}

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

	pid := 0
	if a := s.activeAgent(); a != nil && a.pig.cmd.Process != nil {
		pid = a.pig.cmd.Process.Pid
	}
	_ = out.send(frame{
		"type":     "relay_hello",
		"protocol": protocol,
		"cwd":      s.cwd,
		"uiDir":    s.uiDir,
		"pigPid":   pid,
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
	s.replayAgent(s.activeAgent())
	return out, gen, nil
}

func (s *session) detach(conn net.Conn, gen uint64) {
	s.mu.Lock()
	if s.conn == conn && s.connGen == gen {
		s.conn = nil
		s.out = nil
	}
	s.mu.Unlock()
	_ = conn.Close()
	// No client: keep streaming turns, drop an idle active process so a later
	// switch to history does not need one.
	go func() {
		a := s.activeAgent()
		if a != nil && !a.isBusy() {
			s.stopAgent(a)
		}
	}()
}

func readSessionSummary(path string) (sid, provider, model string, count int) {
	if path == "" {
		return
	}
	f, err := os.Open(path)
	if err != nil {
		return
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 32*1024*1024)
	for sc.Scan() {
		var e struct {
			Type     string `json:"type"`
			ID       string `json:"id"`
			Provider string `json:"provider"`
			ModelID  string `json:"modelId"`
		}
		if json.Unmarshal(sc.Bytes(), &e) != nil {
			continue
		}
		switch e.Type {
		case "session":
			sid = e.ID
		case "model_change":
			if e.Provider != "" || e.ModelID != "" {
				provider, model = e.Provider, e.ModelID
			}
		case "message":
			count++
		}
	}
	return
}

func readSessionMessages(path string) []json.RawMessage {
	out := []json.RawMessage{}
	if path == "" {
		return out
	}
	f, err := os.Open(path)
	if err != nil {
		return out
	}
	defer f.Close()
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 32*1024*1024)
	for sc.Scan() {
		var e struct {
			Type    string          `json:"type"`
			Message json.RawMessage `json:"message"`
		}
		if json.Unmarshal(sc.Bytes(), &e) == nil && e.Type == "message" && len(e.Message) > 0 {
			out = append(out, e.Message)
		}
	}
	if len(out) > 2000 {
		out = out[len(out)-2000:]
	}
	return out
}

func (s *session) syntheticState(id any) frame {
	path := s.activePath
	sid, provider, model, count := readSessionSummary(path)
	return frame{
		"type":        "response",
		"id":          id,
		"command":     "get_state",
		"success":     true,
		"sessionPath": path,
		"data": frame{
			"model": frame{
				"provider":      provider,
				"id":            model,
				"name":          model,
				"contextWindow": 0,
			},
			"thinkingLevel": "off",
			"isStreaming":   false,
			"isCompacting":  false,
			"sessionFile":   path,
			"sessionId":     sid,
			"messageCount":  count,
			"live":          false,
		},
	}
}

func (s *session) syntheticMessages(id any) frame {
	return frame{
		"type":        "response",
		"id":          id,
		"command":     "get_messages",
		"success":     true,
		"sessionPath": s.activePath,
		"data": frame{
			"messages": s.cachedMessages(s.activePath),
		},
	}
}

// cachedMessages avoids re-reading multi-megabyte JSONL files when switching
// back and forth between history sessions.
func (s *session) cachedMessages(path string) []json.RawMessage {
	if path == "" {
		return []json.RawMessage{}
	}
	info, err := os.Stat(path)
	if err != nil {
		return []json.RawMessage{}
	}
	s.msgMu.Lock()
	entry, ok := s.msgCache[path]
	s.msgMu.Unlock()
	if ok && entry.size == info.Size() && entry.mod.Equal(info.ModTime()) {
		return entry.messages
	}
	messages := readSessionMessages(path)
	s.msgMu.Lock()
	s.msgCache[path] = msgCacheEntry{
		size:     info.Size(),
		mod:      info.ModTime(),
		messages: messages,
	}
	s.msgMu.Unlock()
	return messages
}

func (s *session) handleAgentFrame(line []byte, typ string, id any, sessionPath string) {
	switch typ {
	case "switch_session":
		a := s.switchTo(sessionPath)
		_ = s.send(frame{
			"type": "response", "id": id, "command": "switch_session",
			"success": true, "data": frame{},
		})
		s.replayAgent(a)
	case "get_state":
		if a := s.activeAgent(); a != nil {
			_ = a.pig.write(line)
		} else {
			_ = s.send(s.syntheticState(id))
		}
	case "get_messages":
		if a := s.activeAgent(); a != nil {
			_ = a.pig.write(line)
		} else {
			_ = s.send(s.syntheticMessages(id))
		}
	case "get_session_stats":
		if a := s.activeAgent(); a != nil {
			_ = a.pig.write(line)
		} else {
			_ = s.send(frame{
				"type": "response", "id": id, "command": "get_session_stats",
				"success": true, "sessionPath": s.activePath, "data": frame{},
			})
		}
	case "prompt":
		a := s.activeAgent()
		if a == nil {
			var err error
			a, err = s.ensureAgent(s.activePath, s.activePath != "")
			if err != nil {
				_ = s.send(frame{
					"type": "response", "id": id, "command": "prompt",
					"success": false, "error": "cannot start pig: " + err.Error(),
				})
				return
			}
		}
		_ = a.pig.write(line)
	case "new_session":
		a := s.activeAgent()
		if a != nil && !a.isBusy() {
			_ = a.pig.write(line)
			return
		}
		if _, err := s.ensureAgent("", false); err != nil {
			_ = s.send(frame{
				"type": "response", "id": id, "command": "new_session",
				"success": false, "error": "cannot start pig: " + err.Error(),
			})
			return
		}
		_ = s.send(frame{
			"type": "response", "id": id, "command": "new_session",
			"success": true, "data": frame{},
		})
	case "extension_ui_response":
		if a := s.activeAgent(); a != nil {
			var response struct {
				ID string `json:"id"`
			}
			if json.Unmarshal(line, &response) == nil && response.ID != "" {
				a.clearPending(response.ID)
			}
			_ = a.pig.write(line)
		}
	default:
		a := s.activeAgent()
		if a == nil {
			var err error
			a, err = s.ensureAgent(s.activePath, s.activePath != "")
			if err != nil {
				return
			}
		}
		_ = a.pig.write(line)
	}
}

func (s *session) handleClientLine(line []byte) {
	var head struct {
		Type        string `json:"type"`
		ID          any    `json:"id"`
		Token       string `json:"token"`
		Root        string `json:"root"`
		Path        string `json:"path"`
		Name        string `json:"name"`
		SessionPath string `json:"sessionPath"`
	}
	if err := json.Unmarshal(line, &head); err != nil {
		_ = s.send(frame{"type": "relay_error", "message": "invalid json: " + err.Error()})
		return
	}

	if !strings.HasPrefix(head.Type, "relay_") {
		s.handleAgentFrame(line, head.Type, head.ID, head.SessionPath)
		return
	}

	switch head.Type {
	case "relay_auth":
	case "relay_ping":
		_ = s.send(frame{"type": "relay_pong", "id": head.ID})
	case "relay_restart":
		a := s.activeAgent()
		if a != nil {
			path := a.path
			s.stopAgent(a)
			if na, err := s.ensureAgent(path, path != ""); err == nil && na.pig.cmd.Process != nil {
				_ = s.send(frame{"type": "relay_restarted", "pid": na.pig.cmd.Process.Pid})
			}
		} else {
			_ = s.send(frame{"type": "relay_restarted", "pid": 0})
		}
	case "relay_projects":
		root := head.Root
		if root == "" {
			root = s.cwd
		}
		_ = s.send(frame{"type": "relay_projects", "id": head.ID, "root": root, "projects": listProjects(root)})
	case "relay_sessions":
		sessions := listSessions(s.sessionsDir)
		s.agentMu.Lock()
		for _, item := range sessions {
			path, _ := item["path"].(string)
			item["live"] = false
			item["busy"] = false
			if a := s.agents[path]; a != nil {
				item["live"] = true
				item["busy"] = a.isBusy()
			}
		}
		active := s.activePath
		s.agentMu.Unlock()
		_ = s.send(frame{
			"type":     "relay_sessions",
			"id":       head.ID,
			"sessions": sessions,
			"active":   active,
		})
	case "relay_session_rename":
		if err := appendSessionInfo(head.Path, head.Name, s.sessionsDir); err != nil {
			_ = s.send(frame{"type": "relay_session_error", "id": head.ID, "message": "rename: " + err.Error()})
		} else {
			_ = s.send(frame{
				"type": "relay_session_renamed", "id": head.ID,
				"path": head.Path, "name": truncateRunes(head.Name, 80),
			})
		}
	case "relay_session_delete":
		s.agentMu.Lock()
		a := s.agents[head.Path]
		s.agentMu.Unlock()
		if a != nil {
			s.stopAgent(a)
		}
		s.msgMu.Lock()
		delete(s.msgCache, head.Path)
		s.msgMu.Unlock()
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
			"type": "relay_ui_pack", "hash": hash, "files": files,
			"assets": assets, "assetHashes": assetHashes,
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
			"type": "relay_ui_pack", "hash": hash, "files": files,
			"assets": assets, "assetHashes": assetHashes,
		})
	}
}
