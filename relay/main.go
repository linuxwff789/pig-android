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

// buildVersion is the release this binary was built from ("1.1.27"), set by the
// release workflow with -ldflags "-X main.buildVersion=...". The Termux
// installer compares it so the relay matches the app that installed it.
var buildVersion = "dev"

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

// sendRaw forwards a pig frame unchanged.
func (w *connWriter) sendRaw(payload []byte) error {
	w.mu.Lock()
	defer w.mu.Unlock()
	_, err := w.conn.Write(append(payload, '\n'))
	return err
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
		fmt.Println(protocol, buildVersion)
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
	log.Printf("pig-relay %s %s on %s (cwd=%s ui=%s pig=%s)", protocol, buildVersion, listener.Addr(), *cwd, *uiDir, *pigBin)

	agent := newSession(*token, *cwd, *uiDir, sessionsDir, *pigBin)
	go agent.watchUI()
	// Installing the extension the relay carries can compile it (~seconds on a
	// cold pig cache); listening first keeps the handshake fast, startAgent
	// waits for this to finish.
	go agent.installEmbeddedExtensions()

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
