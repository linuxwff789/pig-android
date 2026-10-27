// Install gating.
//
// The Termux installer publishes its progress in ~/.pig/install-status.json.
// The app may reconnect as soon as the relay binary is in place, which is
// still before pig/Go/extensions are finished; starting pig at that moment
// gives the user a half-installed backend (missing /plan, /provider-ui, ...).
// The relay therefore keeps agent requests on hold while the file says
// "running" and forwards the stages to the app as relay_install frames. When
// the file says "done" (or there is no file at all) the normal RPC flow starts.
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"time"
)

type installStatus struct {
	State   string `json:"state"`
	Step    int    `json:"step"`
	Total   int    `json:"total"`
	Message string `json:"message"`
	Updated int64  `json:"updated"` // unix seconds
}

type installGate struct {
	mu     sync.Mutex
	ready  bool
	status frame
	last   string
}

func newInstallGate() *installGate {
	// Ready by default: sessions in tests (and a relay without an installer)
	// must never be held back.
	return &installGate{ready: true}
}

func (g *installGate) finish() {
	g.mu.Lock()
	g.ready = true
	g.mu.Unlock()
}

// reset holds agent requests again; used if a new install starts while the
// relay is already running.
func (g *installGate) reset() {
	g.mu.Lock()
	g.ready = false
	g.mu.Unlock()
}

func (g *installGate) isReady() bool {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.ready
}

// set stores the latest frame and reports whether it differs from the last one
// sent, so the watcher only pushes real changes.
func (g *installGate) set(f frame) bool {
	key := fmt.Sprint(f["state"], "|", f["step"], "|", f["total"], "|", f["message"])
	g.mu.Lock()
	defer g.mu.Unlock()
	if key == g.last {
		return false
	}
	g.last = key
	g.status = f
	return true
}

func (g *installGate) frame() (frame, bool) {
	g.mu.Lock()
	defer g.mu.Unlock()
	return g.status, g.status != nil
}

const installStatusMaxAge = 45 * time.Minute

func (s *session) installStatusPath() string {
	return filepath.Join(s.cwd, ".pig", "install-status.json")
}

// initInstallGate does the first read synchronously: when there is no status
// file the gate must already be open before the first client can ask for
// state, otherwise that first request would be dropped for no reason.
func (s *session) initInstallGate() {
	data, err := os.ReadFile(s.installStatusPath())
	if errors.Is(err, os.ErrNotExist) {
		s.install.finish()
		return
	}
	if err == nil {
		var st installStatus
		if json.Unmarshal(data, &st) == nil && st.State != "" {
			f := installFrame(st)
			s.install.set(f)
			switch st.State {
			case "running":
				if !installStale(st) {
					s.install.reset()
					go s.watchInstall()
					return
				}
			}
		}
	}
	// Missing, unreadable, done, failed or stale: never block a working install.
	s.install.finish()
}

func installFrame(st installStatus) frame {
	return frame{
		"type":    "relay_install",
		"state":   st.State,
		"step":    st.Step,
		"total":   st.Total,
		"message": st.Message,
		"updated": st.Updated,
	}
}

func installStale(st installStatus) bool {
	if st.Updated <= 0 {
		return false
	}
	return time.Since(time.Unix(st.Updated, 0)) > installStatusMaxAge
}

// watchInstall follows the file while it says "running" and pushes changes.
func (s *session) watchInstall() {
	path := s.installStatusPath()
	for {
		data, err := os.ReadFile(path)
		if errors.Is(err, os.ErrNotExist) {
			s.install.finish()
			return
		}
		if err == nil {
			var st installStatus
			if json.Unmarshal(data, &st) == nil && st.State != "" {
				f := installFrame(st)
				changed := s.install.set(f)
				// Open the gate *before* the app sees the done/error frame: its
				// first request may arrive in the same millisecond and would
				// otherwise be dropped, leaving the app stuck on "installing".
				switch st.State {
				case "running":
					s.install.reset()
				case "done", "error":
					s.install.finish()
				}
				if changed {
					_ = s.send(f)
					logf("install: %d/%d %s", st.Step, st.Total, st.Message)
				}
				if st.State == "done" || st.State == "error" {
					return
				}
				if installStale(st) {
					logf("install: status is stale, releasing the agent")
					s.install.finish()
					return
				}
			}
		}
		time.Sleep(500 * time.Millisecond)
	}
}
