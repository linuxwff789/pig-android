package main

import (
	"strings"
	"time"
)

// logLine is one diagnostic line the app can show. The app cannot read this
// process's stderr or Termux's scrollback, so everything that explains a failure
// has to travel over the socket.
type logLine struct {
	Type    string `json:"type"`   // always "relay_log"
	Source  string `json:"source"` // "relay" or "pig"
	Level   string `json:"level"`  // "info" or "error"
	Message string `json:"message"`
	TS      int64  `json:"ts"`
}

const (
	logRingSize   = 400
	stderrTailMax = 40
)

func (s *session) emitLog(source, level, message string) {
	message = strings.TrimSpace(message)
	if message == "" {
		return
	}
	line := logLine{
		Type:    "relay_log",
		Source:  source,
		Level:   level,
		Message: message,
		TS:      time.Now().UnixMilli(),
	}
	s.logMu.Lock()
	s.logRing = append(s.logRing, line)
	if len(s.logRing) > logRingSize {
		s.logRing = append([]logLine(nil), s.logRing[len(s.logRing)-logRingSize:]...)
	}
	s.logMu.Unlock()
	_ = s.send(line)
}

// logHistory is what a freshly connected client gets: the lines it missed,
// including everything the relay said before anyone was listening.
func (s *session) logHistory() []logLine {
	s.logMu.Lock()
	defer s.logMu.Unlock()
	out := make([]logLine, len(s.logRing))
	copy(out, s.logRing)
	return out
}

// logLevel keeps the app from painting every stderr line red.
func logLevel(message string) string {
	lower := strings.ToLower(message)
	for _, needle := range []string{
		"error", "failed", "failure", "cannot", "can't", "refused", "panic",
		"fatal", "not found", "not registered", "denied", "timeout", "exit code",
		"exited", "unable",
	} {
		if strings.Contains(lower, needle) {
			return "error"
		}
	}
	return "info"
}

func (a *agentProc) rememberStderr(line string) {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.stderr = append(a.stderr, line)
	if len(a.stderr) > stderrTailMax {
		a.stderr = append([]string(nil), a.stderr[len(a.stderr)-stderrTailMax:]...)
	}
}

func (a *agentProc) stderrTail() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := make([]string, len(a.stderr))
	copy(out, a.stderr)
	return out
}

func (a *agentProc) lastStderr() string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if len(a.stderr) == 0 {
		return ""
	}
	return a.stderr[len(a.stderr)-1]
}
