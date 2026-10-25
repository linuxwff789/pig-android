package main

import (
	"bufio"
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
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
	// The host pushes setWidget/setStatus/setTitle once and never repeats them,
	// so the last frame per key is kept here: a client that reconnects (the app
	// was killed, not the relay) gets its cards and status line back.
	widgets  map[string]json.RawMessage
	statuses map[string]json.RawMessage
	title    json.RawMessage
	busy     bool
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
	token        string
	cwd          string
	uiDir        string
	sessionsDir  string
	settingsPath string
	pigBin       string

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

	// historyLimits maps a pending get_messages id to the page size the
	// client asked for; the reply (pig's or the disk cache's) is cut to it.
	historyMu     sync.Mutex
	historyLimits map[string]int

	stopOnce sync.Once
	stopped  chan struct{}
	// extReady closes once the extensions the relay carries are written and
	// registered; a pig started before that would miss them.
	extReady chan struct{}
}

func newSession(token, cwd, uiDir, sessionsDir, pigBin string) *session {
	return &session{
		token:         token,
		cwd:           cwd,
		uiDir:         uiDir,
		sessionsDir:   sessionsDir,
		settingsPath:  filepath.Join(filepath.Dir(sessionsDir), "settings.json"),
		pigBin:        pigBin,
		agents:        map[string]*agentProc{},
		msgCache:      map[string]msgCacheEntry{},
		historyLimits: map[string]int{},
		stopped:       make(chan struct{}),
		extReady:      make(chan struct{}),
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
		WidgetKey     string          `json:"widgetKey"`
		WidgetLines   []string        `json:"widgetLines"`
		StatusKey     string          `json:"statusKey"`
		StatusText    *string         `json:"statusText"`
		Title         string          `json:"title"`
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
		// Remember the state-carrying UI frames, including their clear form, so
		// a reconnecting client is not left with a stale card or status line.
		switch head.Method {
		case "setWidget":
			key := head.WidgetKey
			if key == "" {
				key = "widget"
			}
			a.mu.Lock()
			if len(head.WidgetLines) == 0 {
				delete(a.widgets, key)
			} else {
				a.widgets[key] = append([]byte(nil), payload...)
			}
			a.mu.Unlock()
		case "setStatus":
			if head.StatusKey != "" {
				a.mu.Lock()
				if head.StatusText == nil || *head.StatusText == "" {
					delete(a.statuses, head.StatusKey)
				} else {
					a.statuses[head.StatusKey] = append([]byte(nil), payload...)
				}
				a.mu.Unlock()
			}
		case "setTitle":
			a.mu.Lock()
			if head.Title == "" {
				a.title = nil
			} else {
				a.title = append([]byte(nil), payload...)
			}
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
	// The embedded extensions are extracted and registered in the background;
	// the first pig has to see them, or the session starts without /plan.
	<-s.extReady
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
		widgets:     map[string]json.RawMessage{},
		statuses:    map[string]json.RawMessage{},
	}

	go func() {
		scanner := bufio.NewScanner(stdout)
		// Image blocks make `get_messages` frames large; the cap has to cover a
		// transcript with screenshots or pig blocks on a full stdout pipe.
		scanner.Buffer(make([]byte, 64*1024), 64*1024*1024)
		for scanner.Scan() {
			line := scanner.Bytes()
			if len(line) == 0 {
				continue
			}
			payload := append([]byte(nil), line...)
			a.trackFrame(s, payload)
			if s.isActive(a) {
				_ = s.sendRaw(s.fitHistory(s.tagResponse(a, payload)))
			}
		}
		if err := scanner.Err(); err != nil {
			log.Printf("pig stdout scan: %v", err)
			if s.isActive(a) {
				_ = s.sendRaw([]byte(`{"type":"relay_log","message":"pig frame dropped: ` + err.Error() + `"}`))
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
			} else {
				logf("pig stderr: %s", line)
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
		logf("pig exited: code=%d err=%v path=%q", code, err, a.path)
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

// stopEmptyParked stops parked sessions that were never written to disk; a new
// session replaced them, and there is no file to reopen anyway.
func (s *session) stopEmptyParked() {
	s.agentMu.Lock()
	var stale []*agentProc
	for path, a := range s.agents {
		if a == s.active || path == "" || a.isBusy() || sessionHasContent(path) {
			continue
		}
		delete(s.agents, path)
		stale = append(stale, a)
	}
	s.agentMu.Unlock()
	for _, a := range stale {
		go s.stopAgent(a)
	}
}

// promoteEmptyParked turns a parked session that was never written to disk into
// the active agent. It is as empty as a new session, so `new_session` can reuse
// its process instead of starting another one.
func (s *session) promoteEmptyParked() *agentProc {
	s.agentMu.Lock()
	var toStop *agentProc
	for path, a := range s.agents {
		if a == s.active || a.isBusy() || path == "" || sessionHasContent(path) {
			continue
		}
		delete(s.agents, path)
		a.path = ""
		s.agents[""] = a
		old := s.active
		s.active = a
		s.activePath = ""
		if old != nil && old != a && !old.isBusy() && sessionHasContent(old.path) {
			toStop = old
		}
		s.agentMu.Unlock()
		if toStop != nil {
			go s.stopAgent(toStop)
		}
		s.replayAgent(a)
		return a
	}
	s.agentMu.Unlock()
	return nil
}

// switchTo makes path active, reusing a parked agent when it exists. Idle
// agents of the previous session are stopped; history sessions stay read-only.
// An agent whose session was never written to disk (a new conversation the user
// switched away from without sending anything) is parked instead: it is the
// only copy of that session.
func (s *session) switchTo(path string) *agentProc {
	s.agentMu.Lock()
	old := s.active
	a := s.agents[path]
	s.active = a
	s.activePath = path
	s.agentMu.Unlock()
	if old != nil && old != a && !old.isBusy() && sessionHasContent(old.path) {
		s.stopAgent(old)
	}
	return a
}

// sessionHasContent reports whether the session file exists with at least one
// entry, i.e. whether discarding the agent loses anything.
func sessionHasContent(path string) bool {
	if path == "" {
		return true
	}
	info, err := os.Stat(path)
	if err != nil {
		return false
	}
	return info.Size() > 0
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
	title := a.title
	widgets := make(map[string]json.RawMessage, len(a.widgets))
	for key, raw := range a.widgets {
		widgets[key] = raw
	}
	statuses := make(map[string]json.RawMessage, len(a.statuses))
	for key, raw := range a.statuses {
		statuses[key] = raw
	}
	a.mu.Unlock()

	// Card and status frames first: the client drew them before the disconnect
	// and has no way to ask for them again. They are keyed, so replaying an
	// unchanged frame is a no-op in the UI.
	if title != nil {
		_ = s.sendRaw(title)
	}
	for _, key := range sortedRawKeys(widgets) {
		_ = s.sendRaw(widgets[key])
	}
	for _, key := range sortedRawKeys(statuses) {
		_ = s.sendRaw(statuses[key])
	}
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

// sortedRawKeys keeps replayed frames in a stable order, so two reconnects of
// the same agent render identically.
func sortedRawKeys(frames map[string]json.RawMessage) []string {
	keys := make([]string, 0, len(frames))
	for key := range frames {
		keys = append(keys, key)
	}
	sort.Strings(keys)
	return keys
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
	if path == "" {
		return []json.RawMessage{}
	}
	out, _ := scanMessages(path, 0)
	if len(out) > historyLimit {
		out = out[len(out)-historyLimit:]
	}
	return out
}

// readMessagesFrom reads the messages appended after offset.
func readMessagesFrom(path string, offset int64) []json.RawMessage {
	out, err := scanMessages(path, offset)
	if err != nil {
		return nil
	}
	return out
}

// scanMessages reads message entries from offset and reports the new offset.
// The type check is done on the raw line: most session lines are metadata, and
// unmarshalling each of them just to drop it is the expensive part.
func scanMessages(path string, offset int64) ([]json.RawMessage, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	if offset > 0 {
		info, statErr := f.Stat()
		if statErr != nil {
			return nil, statErr
		}
		if info.Size() < offset {
			return nil, io.ErrUnexpectedEOF
		}
		if _, err := f.Seek(offset, io.SeekStart); err != nil {
			return nil, err
		}
	}
	out := []json.RawMessage{}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 64*1024), 32*1024*1024)
	// Match the key, not a serialized spelling: pig writes compact JSON, but a
	// spaced `"type": "message"` (older files, hand edits) must not be dropped.
	messageMarker := []byte(`"message"`)
	for sc.Scan() {
		line := sc.Bytes()
		if !bytes.Contains(line, messageMarker) {
			continue
		}
		var e struct {
			Type    string          `json:"type"`
			Message json.RawMessage `json:"message"`
		}
		if json.Unmarshal(line, &e) == nil && e.Type == "message" && len(e.Message) > 0 {
			out = append(out, e.Message)
		}
	}
	return out, sc.Err()
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
	limit, limited := s.takeHistoryLimit(id)
	all := s.cachedMessages(s.activePath)
	start := 0
	window := all
	if limited {
		window, start = clipHistory(all, limit)
	}
	return frame{
		"type":        "response",
		"id":          id,
		"command":     "get_messages",
		"success":     true,
		"sessionPath": s.activePath,
		"data": frame{
			"messages":     window,
			"historyStart": start,
			"historyTotal": len(all),
		},
	}
}

// historyLimit is how many messages get_messages returns.
const historyLimit = 2000

// historyAlignSlack bounds how far a page may move back to reach the user
// message that opened its first turn.
const historyAlignSlack = 24

// clipHistory keeps the newest `limit` messages of a transcript. The window is
// moved back to the start of the turn that opened it, so a page does not begin
// with a tool result whose call sits on the previous page.
func clipHistory(messages []json.RawMessage, limit int) ([]json.RawMessage, int) {
	if limit <= 0 || len(messages) <= limit {
		return messages, 0
	}
	start := len(messages) - limit
	for i := 0; i < historyAlignSlack && start > 0; i++ {
		if messageRole(messages[start]) == "user" {
			break
		}
		start--
	}
	return messages[start:], start
}

func messageRole(raw json.RawMessage) string {
	var head struct {
		Role string `json:"role"`
	}
	_ = json.Unmarshal(raw, &head)
	return head.Role
}

// wantHistory records the page size requested for a get_messages id. The reply
// is cut when it arrives, whoever produces it.
func (s *session) wantHistory(id any, limit int) {
	key := fmt.Sprint(id)
	s.historyMu.Lock()
	defer s.historyMu.Unlock()
	if s.historyLimits == nil || len(s.historyLimits) > 32 {
		s.historyLimits = map[string]int{}
	}
	s.historyLimits[key] = limit
}

func (s *session) takeHistoryLimit(id any) (int, bool) {
	key := fmt.Sprint(id)
	s.historyMu.Lock()
	defer s.historyMu.Unlock()
	limit, ok := s.historyLimits[key]
	delete(s.historyLimits, key)
	return limit, ok
}

// fitHistory trims a forwarded get_messages reply to the window the app asked
// for. pig answers with the whole transcript, screenshots included, so a
// 4000-message session would otherwise cross the socket as one ~18 MB line and
// be parsed in full on the phone.
func (s *session) fitHistory(payload []byte) []byte {
	if !bytes.Contains(payload, []byte(`"command":"get_messages"`)) {
		return payload
	}
	// One pass into known fields: get_messages replies carry nothing else, and
	// decoding into maps would copy the whole payload twice more.
	var env struct {
		Type        string          `json:"type"`
		ID          json.RawMessage `json:"id"`
		Command     string          `json:"command"`
		Success     bool            `json:"success"`
		Error       string          `json:"error,omitempty"`
		SessionPath string          `json:"sessionPath,omitempty"`
		Data        struct {
			Messages []json.RawMessage `json:"messages"`
		} `json:"data"`
	}
	if err := json.Unmarshal(payload, &env); err != nil {
		return payload
	}
	var id any
	_ = json.Unmarshal(env.ID, &id)
	limit, ok := s.takeHistoryLimit(id)
	if !ok || limit <= 0 {
		return payload
	}
	if !env.Success || len(env.Data.Messages) == 0 {
		return payload
	}
	window, start := clipHistory(env.Data.Messages, limit)
	out, err := json.Marshal(struct {
		Type        string          `json:"type"`
		ID          json.RawMessage `json:"id"`
		Command     string          `json:"command"`
		Success     bool            `json:"success"`
		Error       string          `json:"error,omitempty"`
		SessionPath string          `json:"sessionPath,omitempty"`
		Data        struct {
			Messages     []json.RawMessage `json:"messages"`
			HistoryStart int               `json:"historyStart"`
			HistoryTotal int               `json:"historyTotal"`
		} `json:"data"`
	}{
		Type:        env.Type,
		ID:          env.ID,
		Command:     env.Command,
		Success:     env.Success,
		Error:       env.Error,
		SessionPath: env.SessionPath,
		Data: struct {
			Messages     []json.RawMessage `json:"messages"`
			HistoryStart int               `json:"historyStart"`
			HistoryTotal int               `json:"historyTotal"`
		}{window, start, len(env.Data.Messages)},
	})
	if err != nil {
		return payload
	}
	return out
}

// msgCacheKeep bounds the cache: every entry can hold a few megabytes.
const msgCacheKeep = 4

// cachedMessages avoids re-reading multi-megabyte JSONL files when switching
// back and forth between history sessions.
//
// Session files are append-only, so when the file only grew we read the new
// tail instead of the whole thing. Without that every reconnect after a turn
// paid for the full 10-20 MB file scan.
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
	var messages []json.RawMessage
	if ok && info.Size() > entry.size {
		messages = make([]json.RawMessage, 0, len(entry.messages)+16)
		messages = append(messages, entry.messages...)
		messages = append(messages, readMessagesFrom(path, entry.size)...)
		if len(messages) > historyLimit {
			messages = messages[len(messages)-historyLimit:]
		}
	} else {
		messages = readSessionMessages(path)
	}
	s.msgMu.Lock()
	if len(s.msgCache) >= msgCacheKeep {
		for cachedPath := range s.msgCache {
			if cachedPath != path {
				delete(s.msgCache, cachedPath)
				break
			}
		}
	}
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
		// A session the user created and never wrote to is already empty:
		// reuse its process instead of starting another one.
		if a := s.promoteEmptyParked(); a != nil {
			// Any other unwritten session is superseded by this one.
			s.stopEmptyParked()
			_ = s.send(frame{
				"type": "response", "id": id, "command": "new_session",
				"success": true, "data": frame{},
			})
			return
		}
		a := s.activeAgent()
		if a != nil && !a.isBusy() {
			// pig replaces its runtime in-process (~10s). The app opens the empty
			// conversation and the 正在新建对话 banner right away, so the wait is
			// visible but does not block typing.
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
				// Dropping the frame silently leaves the app waiting forever.
				logf("cannot start pig for %s: %v", typ, err)
				_ = s.send(frame{
					"type": "response", "id": id, "command": typ,
					"success": false, "error": "cannot start pig: " + err.Error(),
				})
				return
			}
		}
		_ = a.pig.write(line)
	}
}

func (s *session) handleClientLine(line []byte) {
	var head struct {
		Type        string         `json:"type"`
		ID          any            `json:"id"`
		Token       string         `json:"token"`
		Root        string         `json:"root"`
		Path        string         `json:"path"`
		Name        string         `json:"name"`
		SessionPath string         `json:"sessionPath"`
		Set         map[string]any `json:"set"`
		Provider    string         `json:"provider"`
		Model       string         `json:"model"`
		Window      *int           `json:"contextWindow"`
		Limit       *int           `json:"limit"`
	}
	if err := json.Unmarshal(line, &head); err != nil {
		_ = s.send(frame{"type": "relay_error", "message": "invalid json: " + err.Error()})
		return
	}

	if head.Type == "get_messages" && head.Limit != nil && *head.Limit > 0 {
		s.wantHistory(head.ID, *head.Limit)
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
		// A session created but not yet written to disk (no message sent) has
		// no file, so the disk scan cannot see it. List it anyway while its pig
		// is alive: otherwise switching away loses it completely.
		known := make(map[string]bool, len(sessions))
		for _, item := range sessions {
			if path, ok := item["path"].(string); ok {
				known[path] = true
			}
		}
		for path, a := range s.agents {
			if path == "" || known[path] || sessionHasContent(path) {
				continue
			}
			sessions = append([]map[string]any{{
				"path":    path,
				"updated": time.Now().UnixMilli(),
				"size":    0,
				"id":      "",
				"cwd":     s.cwd,
				"name":    "",
				"preview": "",
				"live":    true,
				"busy":    a.isBusy(),
				"unsaved": true,
			}}, sessions...)
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
	case "relay_settings":
		handleSettings(s, head.ID, head.Set)
	case "relay_model_override":
		handleModelOverride(s, head.ID, head.Provider, head.Model, head.Window)
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

// settingsKey describes one settings.json key the app may read and write.
type settingsKey struct {
	kind string // "bool", "int", "enum"
	enum []string
	min  int
}

var editableSettings = map[string]settingsKey{
	"compaction.enabled":          {kind: "bool"},
	"compaction.reserveTokens":    {kind: "int", min: 1024},
	"compaction.keepRecentTokens": {kind: "int", min: 1024},
	"cacheWarming":                {kind: "enum", enum: []string{"off", "streaming", "idle"}},
	"hideThinkingBlock":           {kind: "bool"},
	"retry.enabled":               {kind: "bool"},
	"retry.maxRetries":            {kind: "int", min: 0},
	"retry.baseDelayMs":           {kind: "int", min: 0},
	"retry.maxAgentDelayMs":       {kind: "int", min: 0},
	"defaultThinkingLevel": {kind: "enum", enum: []string{
		"off", "minimal", "low", "medium", "high", "xhigh", "max",
	}},
	"thinkingBudgets.minimal": {kind: "int", min: 0},
	"thinkingBudgets.low":     {kind: "int", min: 0},
	"thinkingBudgets.medium":  {kind: "int", min: 0},
	"thinkingBudgets.high":    {kind: "int", min: 0},
}

// handleSettings reads settings.json, or merges the allow-listed keys from the
// frame's `set` object and writes it back. Unknown keys are preserved.
func handleSettings(s *session, id any, set map[string]any) {
	root, err := readSettingsFile(s.settingsPath)
	if err != nil {
		_ = s.send(frame{"type": "relay_settings_error", "id": id, "message": err.Error()})
		return
	}
	if set != nil {
		for path, raw := range set {
			key, ok := editableSettings[path]
			if !ok {
				continue
			}
			value, ok := coerceSetting(key, raw)
			if !ok {
				continue
			}
			setPath(root, path, value)
		}
		if err := writeSettingsFile(s.settingsPath, root); err != nil {
			_ = s.send(frame{"type": "relay_settings_error", "id": id, "message": err.Error()})
			return
		}
	}
	_ = s.send(frame{
		"type":     "relay_settings",
		"id":       id,
		"settings": root,
		"editable": editableSettingsList(),
	})
}

func editableSettingsList() []string {
	list := make([]string, 0, len(editableSettings))
	for key := range editableSettings {
		list = append(list, key)
	}
	sort.Strings(list)
	return list
}

// handleModelOverride pins (or clears, with a zero window) the context window
// of `provider/model` in models.json under
// `providers.<provider>.modelOverrides.<model>.contextWindow`. PiG reads that
// file when it starts, so the value applies to newly spawned agents — the app
// uses it to make a user-pinned context length real instead of display-only.
func handleModelOverride(s *session, id any, provider, model string, window *int) {
	fail := func(msg string) {
		_ = s.send(frame{"type": "relay_model_override_error", "id": id, "message": msg})
	}
	if provider == "" || model == "" {
		fail("provider and model are required")
		return
	}
	if window != nil && (*window < 0 || *window > 10_000_000) {
		fail("contextWindow out of range")
		return
	}
	path := filepath.Join(filepath.Dir(s.settingsPath), "models.json")
	root, err := readSettingsFile(path)
	if err != nil {
		fail(err.Error())
		return
	}
	providers, _ := root["providers"].(map[string]interface{})
	if providers == nil {
		fail("models.json has no providers object")
		return
	}
	entry, _ := providers[provider].(map[string]interface{})
	if entry == nil {
		fail("unknown provider: " + provider)
		return
	}
	overrides, _ := entry["modelOverrides"].(map[string]interface{})
	if overrides == nil {
		overrides = map[string]interface{}{}
		entry["modelOverrides"] = overrides
	}
	fields, _ := overrides[model].(map[string]interface{})
	if fields == nil {
		fields = map[string]interface{}{}
	}
	value := 0
	if window != nil && *window > 0 {
		value = *window
		fields["contextWindow"] = value
		overrides[model] = fields
	} else {
		delete(fields, "contextWindow")
		if len(fields) == 0 {
			delete(overrides, model)
		} else {
			overrides[model] = fields
		}
	}
	if err := writeSettingsFile(path, root); err != nil {
		fail(err.Error())
		return
	}
	_ = s.send(frame{
		"type":          "relay_model_override",
		"id":            id,
		"provider":      provider,
		"model":         model,
		"contextWindow": value,
	})
}

func readSettingsFile(path string) (map[string]interface{}, error) {
	root := map[string]interface{}{}
	data, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return root, nil
		}
		return nil, err
	}
	if len(bytes.TrimSpace(data)) == 0 {
		return root, nil
	}
	if err := json.Unmarshal(data, &root); err != nil {
		return nil, fmt.Errorf("settings.json: %w", err)
	}
	return root, nil
}

func writeSettingsFile(path string, root map[string]interface{}) error {
	data, err := json.MarshalIndent(root, "", "  ")
	if err != nil {
		return err
	}
	if existing, err := os.ReadFile(path); err == nil {
		backup := path + ".bak-" + time.Now().Format("20060102-150405") + "-pre-app"
		if err := os.WriteFile(backup, existing, 0o600); err != nil {
			return err
		}
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, append(data, '\n'), 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}

// coerceSetting accepts the JSON shapes the app sends (numbers arrive as
// float64) and rejects values of the wrong type or outside the allow-list.
func coerceSetting(key settingsKey, raw interface{}) (interface{}, bool) {
	switch key.kind {
	case "bool":
		value, ok := raw.(bool)
		return value, ok
	case "int":
		number, ok := raw.(float64)
		if !ok {
			if n, ok2 := raw.(int); ok2 {
				number, ok = float64(n), true
			}
		}
		if !ok {
			return nil, false
		}
		value := int(number)
		if value < key.min {
			return nil, false
		}
		return value, true
	case "enum":
		value, ok := raw.(string)
		if !ok {
			return nil, false
		}
		for _, allowed := range key.enum {
			if value == allowed {
				return value, true
			}
		}
	}
	return nil, false
}

func setPath(root map[string]interface{}, path string, value interface{}) {
	parts := strings.Split(path, ".")
	node := root
	for _, part := range parts[:len(parts)-1] {
		next, ok := node[part].(map[string]interface{})
		if !ok {
			next = map[string]interface{}{}
			node[part] = next
		}
		node = next
	}
	node[parts[len(parts)-1]] = value
}
