package main

import (
	"archive/tar"
	"bufio"
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestArchiveRoundTrip(t *testing.T) {
	sessions := filepath.Join(t.TempDir(), "sessions")
	project := filepath.Join(sessions, "proj")
	if err := os.MkdirAll(project, 0o700); err != nil {
		t.Fatal(err)
	}
	active := filepath.Join(project, "s1.jsonl")
	if err := os.WriteFile(active, []byte("{}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	archived, err := archiveSessionFile(active, sessions)
	if err != nil {
		t.Fatal(err)
	}
	if !insideArchive(archived, sessions) {
		t.Fatalf("archived file not under the archive: %s", archived)
	}
	if items := listArchived(sessions); len(items) != 1 {
		t.Fatalf("listArchived: %d", len(items))
	}
	restored, err := restoreArchivedFile(archived, sessions)
	if err != nil || restored != active {
		t.Fatalf("restore: %q %v", restored, err)
	}
	if _, err := os.Stat(active); err != nil {
		t.Fatal("restored file missing")
	}
	archived, err = archiveSessionFile(active, sessions)
	if err != nil {
		t.Fatal(err)
	}
	if err := purgeArchivedFile(archived, sessions); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(archived); !os.IsNotExist(err) {
		t.Fatal("purged file still exists")
	}
	if _, err := validateArchivedPath(active, sessions); err == nil {
		t.Fatal("active session accepted as archived path")
	}
}

func insideArchive(path, sessionsDir string) bool {
	_, err := validateArchivedPath(path, sessionsDir)
	return err == nil
}

func TestCoerceSetting(t *testing.T) {
	if value, ok := coerceSetting(settingsKey{kind: "bool"}, true); !ok || value != true {
		t.Fatal("bool rejected")
	}
	if _, ok := coerceSetting(settingsKey{kind: "bool"}, "true"); ok {
		t.Fatal("string accepted as bool")
	}
	if value, ok := coerceSetting(settingsKey{kind: "int", min: 10}, float64(12)); !ok || value != 12 {
		t.Fatal("int rejected")
	}
	if _, ok := coerceSetting(settingsKey{kind: "int", min: 10}, float64(9)); ok {
		t.Fatal("int below min accepted")
	}
	if value, ok := coerceSetting(settingsKey{kind: "enum", enum: []string{"a", "b"}}, "b"); !ok || value != "b" {
		t.Fatal("enum rejected")
	}
	if _, ok := coerceSetting(settingsKey{kind: "enum", enum: []string{"a"}}, "c"); ok {
		t.Fatal("unknown enum accepted")
	}
}

func TestSetPathCreatesNestedMaps(t *testing.T) {
	root := map[string]interface{}{}
	setPath(root, "a.b.c", 3)
	nested, _ := root["a"].(map[string]interface{})
	inner, _ := nested["b"].(map[string]interface{})
	if inner["c"] != 3 {
		t.Fatalf("nested value: %#v", root)
	}
}

func TestReadFrameAndAuthenticate(t *testing.T) {
	server, client := net.Pipe()
	defer client.Close()
	done := make(chan error, 1)
	go func() {
		_, err := authenticate(server, "secret")
		done <- err
	}()
	if _, err := client.Write([]byte(`{"type":"relay_auth","token":"secret"}` + "\n")); err != nil {
		t.Fatal(err)
	}
	select {
	case err := <-done:
		if err != nil {
			t.Fatalf("valid auth rejected: %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("authenticate blocked")
	}

	server2, client2 := net.Pipe()
	defer client2.Close()
	go func() {
		_, _ = authenticate(server2, "secret")
	}()
	_, _ = client2.Write([]byte(`{"type":"relay_auth","token":"wrong"}` + "\n"))

	reader := bufio.NewReaderSize(server, 64)
	big := bytes.Repeat([]byte("x"), 512)
	go func() {
		_, _ = client.Write(big)
		_ = client.Close()
	}()
	if _, err := readFrame(reader, 128); err == nil {
		t.Fatal("oversized frame accepted")
	}
}

func TestChunkWriterChunksAndHashes(t *testing.T) {
	var frames []frame
	w := &chunkWriter{
		send: func(v any) error {
			frames = append(frames, v.(frame))
			return nil
		},
		id:  "b1",
		sha: sha256.New(),
	}
	payload := bytes.Repeat([]byte("a"), backupChunkSize+7)
	if _, err := w.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	if len(frames) != 2 {
		t.Fatalf("frames: %d", len(frames))
	}
	first, _ := base64.StdEncoding.DecodeString(frames[0]["data"].(string))
	if len(first) != backupChunkSize {
		t.Fatalf("first chunk: %d", len(first))
	}
	if got := hex.EncodeToString(w.sha.Sum(nil)); got != w.checksum() {
		t.Fatal("checksum mismatch")
	}
}

func TestSessionMetaAndHelpers(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "s1.jsonl")
	body := `{"type":"session","id":"sess-1","cwd":"/home"}` + "\n" +
		`{"type":"message","message":{"role":"user","content":[{"type":"text","text":"hello world"}]}}` + "\n" +
		`{"type":"session_info","name":"My session"}` + "\n"
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	info, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	meta := readSessionMeta(path, info)
	if meta["id"] != "sess-1" || meta["name"] != "My session" || meta["preview"] != "hello world" {
		t.Fatalf("meta: %#v", meta)
	}
	if got := truncateRunes(" a\nb ", 3); got != "a b" && got != " a " {
		t.Fatalf("truncateRunes: %q", got)
	}
	if !isBinary([]byte{0, 1, 2}) || isBinary([]byte("text")) {
		t.Fatal("isBinary")
	}
	if name := backupFileName("x"); len(name) < len("x-") {
		t.Fatalf("backupFileName: %q", name)
	}
}

func TestScanUITextAndAssets(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "manifest.json"), []byte(`{"name":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(dir, "assets"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "assets", "a.png"), []byte{1, 2, 3}, 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, ".hidden"), []byte("no"), 0o644); err != nil {
		t.Fatal(err)
	}
	files, assets, hashes, hash, err := scanUI(dir)
	if err != nil {
		t.Fatal(err)
	}
	if files["manifest.json"] == "" || assets["assets/a.png"] == "" ||
		hashes["assets/a.png"] == "" || hash == "" {
		t.Fatalf("scanUI: files=%v assets=%v hashes=%v hash=%q", files, assets, hashes, hash)
	}
	if _, ok := files[".hidden"]; ok {
		t.Fatal("hidden file included")
	}
}

func TestProviderHelpers(t *testing.T) {
	if got := redactProviderText("https://x/models?key=abc&x=1"); bytes.Contains([]byte(got), []byte("abc")) {
		t.Fatalf("key not redacted: %s", got)
	}
	if !providerHasHeader(map[string]string{"Authorization": "x"}, "authorization") {
		t.Fatal("header match is not case-insensitive")
	}
	if models := providerExtractModels(map[string]any{"data": []any{map[string]any{"id": "m"}}}); len(models) != 1 {
		t.Fatalf("extractModels: %v", models)
	}
	if got := providerString(map[string]any{"x": "  y  "}, "x"); got != "y" {
		t.Fatalf("providerString: %q", got)
	}
}

func newTestSession(t *testing.T) *session {
	t.Helper()
	root := t.TempDir()
	s := &session{
		cwd:           root,
		sessionsDir:   filepath.Join(root, "sessions"),
		settingsPath:  filepath.Join(root, "agent", "settings.json"),
		agents:        map[string]*agentProc{},
		msgCache:      map[string]msgCacheEntry{},
		historyLimits: map[string]int{},
		install:       newInstallGate(),
	}
	ready := make(chan struct{})
	close(ready)
	s.extReady = ready
	if err := os.MkdirAll(s.sessionsDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(s.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
	return s
}

func testSessionWithConn(t *testing.T) (*session, *bufio.Reader) {
	t.Helper()
	s := newTestSession(t)
	server, client := net.Pipe()
	t.Cleanup(func() {
		_ = server.Close()
		_ = client.Close()
	})
	s.conn = server
	s.out = &connWriter{conn: server}
	s.connGen = 1
	return s, bufio.NewReader(client)
}

func readJSONFrame(t *testing.T, reader *bufio.Reader) map[string]any {
	t.Helper()
	line, err := reader.ReadBytes('\n')
	if err != nil {
		t.Fatalf("read frame: %v", err)
	}
	var out map[string]any
	if err := jsonUnmarshal(line, &out); err != nil {
		t.Fatalf("frame is not JSON: %s", line)
	}
	return out
}

func jsonUnmarshal(data []byte, out any) error {
	return json.Unmarshal(data, out)
}

func TestRelayPingAndSettings(t *testing.T) {
	s, reader := testSessionWithConn(t)
	if err := os.WriteFile(s.settingsPath, []byte(`{"compaction":{"enabled":false}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	go s.handleClientLine([]byte(`{"type":"relay_ping","id":"p1"}`))
	pong := readJSONFrame(t, reader)
	if pong["type"] != "relay_pong" || pong["id"] != "p1" {
		t.Fatalf("pong: %#v", pong)
	}

	go s.handleClientLine([]byte(`{"type":"relay_settings","id":"s1","set":{"compaction.enabled":true}}`))
	settings := readJSONFrame(t, reader)
	if settings["type"] != "relay_settings" {
		t.Fatalf("settings frame: %#v", settings)
	}
	root, err := readSettingsFile(s.settingsPath)
	if err != nil {
		t.Fatal(err)
	}
	compaction, _ := root["compaction"].(map[string]any)
	if compaction["enabled"] != true {
		t.Fatalf("setting not persisted: %#v", root)
	}
}

func TestRelaySessionLists(t *testing.T) {
	s, reader := testSessionWithConn(t)
	project := filepath.Join(s.sessionsDir, "proj")
	if err := os.MkdirAll(project, 0o700); err != nil {
		t.Fatal(err)
	}
	file := filepath.Join(project, "s1.jsonl")
	if err := os.WriteFile(file, []byte(`{"type":"session","id":"s1"}`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}

	go s.handleClientLine([]byte(`{"type":"relay_sessions","id":"l1"}`))
	listed := readJSONFrame(t, reader)
	if listed["type"] != "relay_sessions" {
		t.Fatalf("sessions frame: %#v", listed)
	}
	items, _ := listed["sessions"].([]any)
	if len(items) != 1 {
		t.Fatalf("sessions: %#v", items)
	}

	archived, err := archiveSessionFile(file, s.sessionsDir)
	if err != nil {
		t.Fatal(err)
	}
	go s.handleClientLine([]byte(`{"type":"relay_archived_sessions","id":"a1"}`))
	frame := readJSONFrame(t, reader)
	items, _ = frame["sessions"].([]any)
	if len(items) != 1 {
		t.Fatalf("archived: %#v (archived path %s)", items, archived)
	}
	if _, err := validateArchivedPath(archived, s.sessionsDir); err != nil {
		t.Fatal(err)
	}
}

func TestHandleAgentFrameWithoutPig(t *testing.T) {
	s, reader := testSessionWithConn(t)
	s.install.reset()
	go s.handleAgentFrame([]byte(`{"type":"get_state","id":"g0"}`), "get_state", "g0", "")
	if frame := readJSONFrame(t, reader); frame["success"] != false {
		t.Fatalf("install gate: %#v", frame)
	}
	s.install.finish()

	go s.handleAgentFrame([]byte(`{"type":"switch_session","id":"sw0","sessionPath":"/etc/passwd"}`), "switch_session", "sw0", "/etc/passwd")
	if frame := readJSONFrame(t, reader); frame["success"] != false {
		t.Fatalf("invalid session path accepted: %#v", frame)
	}

	project := filepath.Join(s.sessionsDir, "proj")
	if err := os.MkdirAll(project, 0o700); err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(project, "s.jsonl")
	body := `{"type":"session","id":"sid","cwd":"/x"}` + "\n"
	for i := 0; i < 4; i++ {
		body += fmt.Sprintf(`{"type":"message","message":{"role":"user","content":[{"type":"text","text":"m%d"}]}}`+"\n", i)
	}
	if err := os.WriteFile(path, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	switchLine := []byte(fmt.Sprintf(`{"type":"switch_session","id":"sw1","sessionPath":%q}`, path))
	go s.handleAgentFrame(switchLine, "switch_session", "sw1", path)
	if frame := readJSONFrame(t, reader); frame["success"] != true {
		t.Fatalf("switch_session: %#v", frame)
	}
	if got := s.activePathValue(); got != path {
		t.Fatalf("active path: %q", got)
	}

	go s.handleClientLine([]byte(fmt.Sprintf(`{"type":"get_messages","id":"m1","limit":2,"sessionPath":%q}`, path)))
	frame := readJSONFrame(t, reader)
	data, _ := frame["data"].(map[string]any)
	messages, _ := data["messages"].([]any)
	if len(messages) != 2 || data["historyTotal"] != float64(4) {
		t.Fatalf("limited history: %#v", frame)
	}

	go s.handleAgentFrame([]byte(`{"type":"get_state","id":"g1"}`), "get_state", "g1", path)
	frame = readJSONFrame(t, reader)
	data, _ = frame["data"].(map[string]any)
	if data["sessionId"] != "sid" || data["messageCount"] != float64(4) {
		t.Fatalf("synthetic state: %#v", frame)
	}

	empty := newTestSession(t)
	server, client := net.Pipe()
	t.Cleanup(func() { _ = server.Close(); _ = client.Close() })
	empty.conn, empty.out, empty.connGen = server, &connWriter{conn: server}, 1
	emptyReader := bufio.NewReader(client)
	go empty.handleAgentFrame([]byte(`{"type":"get_available_models","id":"am"}`), "get_available_models", "am", "")
	frame = readJSONFrame(t, emptyReader)
	data, _ = frame["data"].(map[string]any)
	if models, _ := data["models"].([]any); len(models) != 0 {
		t.Fatalf("empty model list: %#v", frame)
	}
	go empty.handleAgentFrame([]byte(`{"type":"prompt","id":"p1"}`), "prompt", "p1", "")
	if frame := readJSONFrame(t, emptyReader); frame["success"] != false {
		t.Fatalf("prompt without pig: %#v", frame)
	}
	go empty.handleAgentFrame([]byte(`{"type":"new_session","id":"n1"}`), "new_session", "n1", "")
	if frame := readJSONFrame(t, emptyReader); frame["success"] != false {
		t.Fatalf("new_session without pig: %#v", frame)
	}
}

func TestFitHistoryClipsToWindow(t *testing.T) {
	s := newTestSession(t)
	payload := `{"type":"response","id":"h1","command":"get_messages","success":true,"data":{"messages":[` +
		`{"role":"user","content":"a"},{"role":"user","content":"b"},{"role":"user","content":"c"},{"role":"user","content":"d"}]}}`
	s.wantHistory("h1", 2)
	out := s.fitHistory([]byte(payload))
	var env struct {
		Data struct {
			Messages     []json.RawMessage `json:"messages"`
			HistoryStart int               `json:"historyStart"`
			HistoryTotal int               `json:"historyTotal"`
		} `json:"data"`
	}
	if err := json.Unmarshal(out, &env); err != nil {
		t.Fatal(err)
	}
	if len(env.Data.Messages) != 2 || env.Data.HistoryStart != 2 || env.Data.HistoryTotal != 4 {
		t.Fatalf("fitHistory: %s", out)
	}
	// A page starts at the user message that opened its first turn, even if
	// that means returning more than the requested limit.
	aligned := `{"type":"response","id":"h2","command":"get_messages","success":true,"data":{"messages":[` +
		`{"role":"user"},{"role":"assistant"},{"role":"assistant"}]}}`
	s.wantHistory("h2", 2)
	out = s.fitHistory([]byte(aligned))
	if err := json.Unmarshal(out, &env); err != nil {
		t.Fatal(err)
	}
	if len(env.Data.Messages) != 3 || env.Data.HistoryStart != 0 {
		t.Fatalf("aligned page: %s", out)
	}
	if got := string(s.fitHistory([]byte(payload))); got != payload {
		t.Fatal("history without a limit must pass through")
	}
}

func TestTagResponseOnlyForStateFrames(t *testing.T) {
	s := newTestSession(t)
	a := &agentProc{path: filepath.Join(s.sessionsDir, "p", "s.jsonl")}
	payload := []byte(`{"type":"response","command":"get_messages","success":true,"data":{}}`)
	tagged := string(s.tagResponse(a, payload))
	if !bytes.Contains([]byte(tagged), []byte(`"sessionPath"`)) || tagged[0] != '{' {
		t.Fatalf("tagged: %s", tagged)
	}
	other := []byte(`{"type":"response","command":"prompt","success":true}`)
	if got := string(s.tagResponse(a, other)); got != string(other) {
		t.Fatalf("unexpected tag: %s", got)
	}
}

func TestAttachAndDetach(t *testing.T) {
	s := newTestSession(t)
	s.uiDir = t.TempDir()
	if err := os.WriteFile(filepath.Join(s.uiDir, "manifest.json"), []byte(`{"name":"x"}`), 0o644); err != nil {
		t.Fatal(err)
	}
	server, client := net.Pipe()
	type attached struct {
		gen uint64
		err error
	}
	done := make(chan attached, 1)
	go func() {
		_, gen, err := s.attach(server)
		done <- attached{gen, err}
	}()
	reader := bufio.NewReader(client)
	hello := readJSONFrame(t, reader)
	if hello["type"] != "relay_hello" || hello["cwd"] != s.cwd {
		t.Fatalf("hello: %#v", hello)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_logs" {
		t.Fatalf("logs: %#v", frame)
	}
	if frame := readJSONFrame(t, reader); frame["type"] != "relay_ui_pack" || frame["hash"] == "" {
		t.Fatalf("ui pack: %#v", frame)
	}
	result := <-done
	if result.err != nil {
		t.Fatal(result.err)
	}
	if s.currentOut() == nil {
		t.Fatal("attach did not install the writer")
	}
	s.detach(server, result.gen)
	if s.currentOut() != nil {
		t.Fatal("detach left the writer installed")
	}
	_ = client.Close()

	// A late detach from the previous connection must not clear the new one.
	server2, client2 := net.Pipe()
	defer client2.Close()
	go func() {
		_, gen2, err := s.attach(server2)
		_ = gen2
		if err != nil {
			t.Error(err)
		}
	}()
	reader2 := bufio.NewReader(client2)
	for i := 0; i < 3; i++ {
		readJSONFrame(t, reader2)
	}
	s.detach(server, result.gen)
	if s.currentOut() == nil {
		t.Fatal("stale detach cleared the current writer")
	}
	_ = server2.Close()
}

type bufferWriteCloser struct{ io.Writer }

func (bufferWriteCloser) Close() error { return nil }

func testAgent(path string) (*agentProc, *bytes.Buffer) {
	buf := &bytes.Buffer{}
	a := &agentProc{
		pig: &pigProcess{
			stdin: bufferWriteCloser{buf},
			cmd:   &exec.Cmd{},
			dead:  make(chan struct{}),
		},
		path:        path,
		activeTools: map[string]toolProgress{},
		pendingUI:   map[string]json.RawMessage{},
		widgets:     map[string]json.RawMessage{},
		statuses:    map[string]json.RawMessage{},
	}
	return a, buf
}

func TestHandleAgentFrameWithActiveAgent(t *testing.T) {
	s, reader := testSessionWithConn(t)
	path := filepath.Join(s.sessionsDir, "proj", "s.jsonl")
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(`{"type":"session","id":"sid"}`+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	a, written := testAgent(path)
	s.agents[path], s.active, s.activePath = a, a, path

	for _, typ := range []string{"get_state", "get_messages", "get_session_stats", "prompt", "get_available_models"} {
		line := []byte(fmt.Sprintf(`{"type":%q,"id":"x"}`, typ))
		s.handleAgentFrame(line, typ, "x", path)
	}
	if got := written.String(); !bytes.Contains([]byte(got), []byte(`"get_state"`)) ||
		!bytes.Contains([]byte(got), []byte(`"prompt"`)) {
		t.Fatalf("frames not forwarded: %s", got)
	}

	a.pendingUI["ui1"] = json.RawMessage(`{"type":"extension_ui_request","id":"ui1"}`)
	s.handleAgentFrame([]byte(`{"type":"extension_ui_response","id":"ui1"}`), "extension_ui_response", nil, path)
	a.mu.Lock()
	_, stillPending := a.pendingUI["ui1"]
	a.mu.Unlock()
	if stillPending {
		t.Fatal("extension_ui_response did not clear the pending request")
	}

	// Switching to the same path reuses the agent and replies.
	go s.handleAgentFrame([]byte(`{"type":"switch_session","id":"sw"}`), "switch_session", "sw", path)
	if frame := readJSONFrame(t, reader); frame["success"] != true {
		t.Fatalf("switch to same path: %#v", frame)
	}

	// Switching to another path parks/stops the idle previous agent.
	other := filepath.Join(s.sessionsDir, "proj", "other.jsonl")
	go s.handleAgentFrame([]byte(`{"type":"switch_session","id":"sw2"}`), "switch_session", "sw2", other)
	if frame := readJSONFrame(t, reader); frame["success"] != true {
		t.Fatalf("switch to other path: %#v", frame)
	}
	if s.activeAgent() != nil {
		t.Fatal("other session unexpectedly has an agent")
	}
}

func TestReplayAgentFrames(t *testing.T) {
	s, reader := testSessionWithConn(t)
	a, _ := testAgent("/tmp/s.jsonl")
	a.title = json.RawMessage(`{"type":"extension_ui_request","method":"setTitle","title":"T"}`)
	a.widgets["w"] = json.RawMessage(`{"type":"extension_ui_request","method":"setWidget","widgetKey":"w","widgetLines":["x"]}`)
	a.statuses["s"] = json.RawMessage(`{"type":"extension_ui_request","method":"setStatus","statusKey":"s","statusText":"S"}`)
	a.pendingUI["p"] = json.RawMessage(`{"type":"extension_ui_request","id":"p","method":"confirm"}`)
	a.streamText = "partial"
	a.activeTools["t1"] = toolProgress{Name: "bash", Partial: json.RawMessage(`{"x":1}`)}

	go s.replayAgent(a)
	types := []string{}
	for i := 0; i < 6; i++ {
		frame := readJSONFrame(t, reader)
		types = append(types, fmt.Sprint(frame["type"], ":", frame["method"]))
	}
	want := []string{
		"extension_ui_request:setTitle",
		"extension_ui_request:setWidget",
		"extension_ui_request:setStatus",
		"extension_ui_request:confirm",
		"relay_resume:<nil>",
		"relay_tool:<nil>",
	}
	for i := range want {
		if types[i] != want[i] {
			t.Fatalf("replay order: %v", types)
		}
	}
}

type memAddr struct{}

func (memAddr) Network() string { return "mem" }
func (memAddr) String() string  { return "mem" }

// memConn is a non-blocking net.Conn: relay sends land in a buffer, so a test
// can call a handler synchronously and read the frames afterwards.
type memConn struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (m *memConn) Read([]byte) (int, error) { return 0, io.EOF }
func (m *memConn) Write(p []byte) (int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.buf.Write(p)
}
func (m *memConn) Close() error                     { return nil }
func (m *memConn) LocalAddr() net.Addr              { return memAddr{} }
func (m *memConn) RemoteAddr() net.Addr             { return memAddr{} }
func (m *memConn) SetDeadline(time.Time) error      { return nil }
func (m *memConn) SetReadDeadline(time.Time) error  { return nil }
func (m *memConn) SetWriteDeadline(time.Time) error { return nil }
func (m *memConn) frames(t *testing.T) []map[string]any {
	t.Helper()
	m.mu.Lock()
	data := m.buf.String()
	m.buf.Reset()
	m.mu.Unlock()
	var out []map[string]any
	for _, line := range bytes.Split([]byte(data), []byte("\n")) {
		if len(bytes.TrimSpace(line)) == 0 {
			continue
		}
		var frame map[string]any
		if err := json.Unmarshal(line, &frame); err != nil {
			t.Fatalf("bad frame %q: %v", line, err)
		}
		out = append(out, frame)
	}
	return out
}

func memSession(t *testing.T) (*session, *memConn) {
	t.Helper()
	s := newTestSession(t)
	m := &memConn{}
	s.conn, s.out, s.connGen = m, &connWriter{conn: m}, 1
	return s, m
}

func makeTarGz(t *testing.T, entries map[string]string) ([]byte, string) {
	t.Helper()
	var buf bytes.Buffer
	gz := gzip.NewWriter(&buf)
	tw := tar.NewWriter(gz)
	for name, content := range entries {
		if err := tw.WriteHeader(&tar.Header{
			Name:     name,
			Mode:     0o600,
			Size:     int64(len(content)),
			Typeflag: tar.TypeReg,
		}); err != nil {
			t.Fatal(err)
		}
		if _, err := tw.Write([]byte(content)); err != nil {
			t.Fatal(err)
		}
	}
	if err := tw.Close(); err != nil {
		t.Fatal(err)
	}
	if err := gz.Close(); err != nil {
		t.Fatal(err)
	}
	sum := sha256.Sum256(buf.Bytes())
	return buf.Bytes(), hex.EncodeToString(sum[:])
}

func TestRestoreRoundTrip(t *testing.T) {
	s, m := memSession(t)
	root := s.cwd
	s.sessionsDir = filepath.Join(root, ".pig", "agent", "sessions")
	if err := os.MkdirAll(s.sessionsDir, 0o700); err != nil {
		t.Fatal(err)
	}

	archive, sum := makeTarGz(t, map[string]string{
		".pig/agent/settings.json":      `{"packages":[]}`,
		".pig/agent/sessions/p/s.jsonl": `{"type":"session","id":"restored"}` + "\n",
		"../escape":                     "no",
	})
	s.beginRestore("r1", "backup.tar.gz", int64(len(archive)))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_ready" {
		t.Fatalf("beginRestore: %#v", frame)
	}
	s.restoreChunk("r1", base64.StdEncoding.EncodeToString(archive[:len(archive)/2]))
	s.restoreChunk("r1", base64.StdEncoding.EncodeToString(archive[len(archive)/2:]))
	s.endRestore("r1", sum)
	var frame map[string]any
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		frames := m.frames(t)
		if len(frames) > 0 {
			frame = frames[len(frames)-1]
			break
		}
		time.Sleep(10 * time.Millisecond)
	}
	if frame == nil || frame["type"] != "relay_restore_done" {
		t.Fatalf("endRestore: %#v", frame)
	}
	body, err := os.ReadFile(filepath.Join(root, ".pig", "agent", "settings.json"))
	if err != nil || string(body) != `{"packages":[]}` {
		t.Fatalf("restored settings: %q %v", body, err)
	}
	sessionBody, err := os.ReadFile(filepath.Join(root, ".pig", "agent", "sessions", "p", "s.jsonl"))
	if err != nil || !bytes.Contains(sessionBody, []byte("restored")) {
		t.Fatalf("restored session: %q %v", sessionBody, err)
	}
	if _, err := os.Stat(filepath.Join(root, "escape")); !os.IsNotExist(err) {
		t.Fatal("archive entry outside .pig was extracted")
	}
	if backup, _ := frame["backup"].(string); backup == "" {
		t.Fatal("automatic backup path missing")
	} else if _, err := os.Stat(backup); err != nil {
		t.Fatalf("automatic backup missing: %v", err)
	}
	s.transfer.mu.Lock()
	busy := s.transfer.busy
	s.transfer.mu.Unlock()
	if busy {
		t.Fatal("transfer still busy after restore")
	}
}

func TestRestoreRejectsBadUploads(t *testing.T) {
	s, m := memSession(t)
	s.restoreChunk("nope", "AAAA")
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" {
		t.Fatalf("chunk without restore: %#v", frame)
	}

	archive, _ := makeTarGz(t, map[string]string{".pig/x": "x"})
	s.beginRestore("r1", "b.tar.gz", int64(len(archive)))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_ready" {
		t.Fatalf("begin: %#v", frame)
	}
	s.beginRestore("r2", "b.tar.gz", int64(len(archive)))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" {
		t.Fatalf("second restore accepted: %#v", frame)
	}
	s.restoreChunk("other", base64.StdEncoding.EncodeToString(archive))
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" {
		t.Fatalf("wrong restore id accepted: %#v", frame)
	}
	s.restoreChunk("r1", base64.StdEncoding.EncodeToString(archive))
	if frames := m.frames(t); len(frames) != 0 {
		t.Fatalf("chunk should not reply: %#v", frames)
	}
	s.endRestore("r1", "deadbeef")
	if frame := m.frames(t)[0]; frame["type"] != "relay_restore_error" {
		t.Fatalf("bad checksum accepted: %#v", frame)
	}
	s.transfer.mu.Lock()
	busy := s.transfer.busy
	s.transfer.mu.Unlock()
	if busy {
		t.Fatal("failed restore kept the transfer busy")
	}
}
