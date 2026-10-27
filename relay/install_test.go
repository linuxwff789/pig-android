package main

import (
	"path/filepath"
	"testing"
	"time"
)

func TestInstallGateMissingStatusIsReady(t *testing.T) {
	s := testSession(t, t.TempDir())
	s.initInstallGate()
	if !s.install.isReady() {
		t.Fatal("a relay without an install status must accept agents")
	}
}

func TestInstallGateHoldsUntilDone(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	path := filepath.Join(home, ".pig", "install-status.json")
	writeFile(t, path, `{"state":"running","step":3,"total":9,"message":"installing pig","updated":`+
		itoaTest(time.Now().Unix())+`}`)

	s.initInstallGate()
	if s.install.isReady() {
		t.Fatal("running install must hold agent requests")
	}
	if _, err := s.startAgent("", false); err == nil {
		t.Fatal("startAgent should refuse while installing")
	}

	writeFile(t, path, `{"state":"done","step":9,"total":9,"message":"ready","updated":`+
		itoaTest(time.Now().Unix())+`}`)
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if s.install.isReady() {
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !s.install.isReady() {
		t.Fatal("done status did not release the gate")
	}
	status, ok := s.install.frame()
	if !ok || status["state"] != "done" {
		t.Fatalf("status = %v %v", status, ok)
	}
}

func TestInstallGateStaleStatusIsReady(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	writeFile(t, filepath.Join(home, ".pig", "install-status.json"),
		`{"state":"running","step":1,"total":9,"message":"old","updated":1}`)
	s.initInstallGate()
	if !s.install.isReady() {
		t.Fatal("a stale running status must not block forever")
	}
}

func itoaTest(value int64) string {
	if value == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for value > 0 {
		i--
		buf[i] = byte('0' + value%10)
		value /= 10
	}
	return string(buf[i:])
}
