// pig-relay bridges the Android app to a `pig --mode rpc` process.
//
// Wire format: one JSON object per line over loopback TCP. Frames produced by
// pig are forwarded verbatim. Relay control frames use the "relay_" type prefix.
package main

import (
	"bufio"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
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
	conn    net.Conn
	out     *connWriter
	token   string
	cwd     string
	uiDir   string
	pigBin  string
	closed  chan struct{}
	closeMu sync.Once

	pigMu sync.Mutex
	pig   *pigProcess
}

func (s *session) shutdown() {
	s.closeMu.Do(func() { close(s.closed) })
	s.pigMu.Lock()
	pig := s.pig
	s.pig = nil
	s.pigMu.Unlock()
	if pig != nil {
		pig.stop()
	}
	_ = s.conn.Close()
}

func (s *session) currentPig() *pigProcess {
	s.pigMu.Lock()
	defer s.pigMu.Unlock()
	return s.pig
}

func (s *session) startPig() (*pigProcess, error) {
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
			// Forward pig protocol frames unchanged.
			payload := append([]byte(nil), line...)
			if err := s.out.sendRaw(payload); err != nil {
				return
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
			_ = s.out.send(frame{"type": "relay_log", "message": line})
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
		_ = s.out.send(frame{"type": "relay_exit", "code": code})
	}()

	s.pigMu.Lock()
	s.pig = pig
	s.pigMu.Unlock()
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
	s.pigMu.Unlock()
	if old != nil {
		old.stop()
	}
	pig, err := s.startPig()
	if err != nil {
		_ = s.out.send(frame{"type": "relay_error", "message": "cannot start pig: " + err.Error()})
		return err
	}
	_ = s.out.send(frame{"type": "relay_restarted", "pid": pig.cmd.Process.Pid})
	return nil
}

func (s *session) handleClientLine(line []byte) {
	var head struct {
		Type  string `json:"type"`
		ID    any    `json:"id"`
		Token string `json:"token"`
		Root  string `json:"root"`
	}
	if err := json.Unmarshal(line, &head); err != nil {
		_ = s.out.send(frame{"type": "relay_error", "message": "invalid json: " + err.Error()})
		return
	}

	if !strings.HasPrefix(head.Type, "relay_") {
		pig := s.currentPig()
		if pig == nil {
			_ = s.out.send(frame{"type": "relay_error", "message": "pig is not running"})
			return
		}
		if err := pig.write(line); err != nil {
			_ = s.out.send(frame{"type": "relay_error", "message": "pig write failed: " + err.Error()})
		}
		return
	}

	switch head.Type {
	case "relay_auth":
		// Already authenticated before this loop.
	case "relay_ping":
		_ = s.out.send(frame{"type": "relay_pong", "id": head.ID})
	case "relay_restart":
		_ = s.restartPig()
	case "relay_projects":
		root := head.Root
		if root == "" {
			root = s.cwd
		}
		_ = s.out.send(frame{"type": "relay_projects", "id": head.ID, "root": root, "projects": listProjects(root)})
	case "relay_ui":
		files, hash, err := scanUI(s.uiDir)
		if err != nil {
			_ = s.out.send(frame{"type": "relay_error", "message": "ui pack: " + err.Error()})
			return
		}
		_ = s.out.send(frame{"type": "relay_ui_pack", "hash": hash, "files": files})
	case "relay_stop":
		s.shutdown()
	default:
		_ = s.out.send(frame{"type": "relay_error", "message": "unknown relay frame: " + head.Type})
	}
}

func (s *session) watchUI() {
	last := ""
	for {
		select {
		case <-s.closed:
			return
		case <-time.After(800 * time.Millisecond):
		}
		files, hash, err := scanUI(s.uiDir)
		if err != nil || hash == last {
			continue
		}
		last = hash
		_ = s.out.send(frame{"type": "relay_ui_pack", "hash": hash, "files": files})
	}
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
func scanUI(dir string) (map[string]string, string, error) {
	files := map[string]string{}
	if dir == "" {
		return files, "", nil
	}
	info, err := os.Stat(dir)
	if err != nil || !info.IsDir() {
		return files, "", nil
	}

	type entry struct {
		rel  string
		data []byte
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
		if err != nil || isBinary(data) {
			return nil
		}
		rel, err := filepath.Rel(dir, path)
		if err != nil {
			return nil
		}
		entries = append(entries, entry{rel: filepath.ToSlash(rel), data: data})
		return nil
	})
	if err != nil {
		return files, "", err
	}

	sort.Slice(entries, func(i, j int) bool { return entries[i].rel < entries[j].rel })
	h := sha256.New()
	for _, e := range entries {
		files[e.rel] = string(e.data)
		h.Write([]byte(e.rel))
		h.Write([]byte{0})
		h.Write(e.data)
		h.Write([]byte{0})
	}
	return files, hex.EncodeToString(h.Sum(nil)), nil
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

	listener, err := net.Listen("tcp", fmt.Sprintf("%s:%d", *listen, *port))
	if err != nil {
		log.Fatalf("listen: %v", err)
	}
	log.Printf("pig-relay %s on %s (cwd=%s ui=%s pig=%s)", protocol, listener.Addr(), *cwd, *uiDir, *pigBin)

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		_ = listener.Close()
	}()

	for {
		conn, err := listener.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			return
		}
		go serve(conn, *token, *cwd, *uiDir, *pigBin)
	}
}

func serve(conn net.Conn, token, cwd, uiDir, pigBin string) {
	defer conn.Close()

	reader, err := authenticate(conn, token)
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

	s := &session{
		conn:   conn,
		out:    &connWriter{conn: conn},
		token:  token,
		cwd:    cwd,
		uiDir:  uiDir,
		pigBin: pigBin,
		closed: make(chan struct{}),
	}
	defer s.shutdown()

	pig, err := s.startPig()
	if err != nil {
		_ = s.out.send(frame{"type": "relay_error", "message": "cannot start pig: " + err.Error()})
		return
	}
	_ = s.out.send(frame{
		"type":     "relay_hello",
		"protocol": protocol,
		"cwd":      cwd,
		"uiDir":    uiDir,
		"pigPid":   pig.cmd.Process.Pid,
	})
	go s.watchUI()

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
