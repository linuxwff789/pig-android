package main

import (
	"bufio"
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net"
	"os"
	"path/filepath"
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

func testSessionWithConn(t *testing.T) (*session, *bufio.Reader) {
	t.Helper()
	server, client := net.Pipe()
	t.Cleanup(func() {
		_ = server.Close()
		_ = client.Close()
	})
	root := t.TempDir()
	s := &session{
		cwd:           root,
		sessionsDir:   filepath.Join(root, "sessions"),
		settingsPath:  filepath.Join(root, "agent", "settings.json"),
		agents:        map[string]*agentProc{},
		msgCache:      map[string]msgCacheEntry{},
		historyLimits: map[string]int{},
		install:       newInstallGate(),
		conn:          server,
		out:           &connWriter{conn: server},
		connGen:       1,
	}
	if err := os.MkdirAll(s.sessionsDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(s.settingsPath), 0o700); err != nil {
		t.Fatal(err)
	}
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
