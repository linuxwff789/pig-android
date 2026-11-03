package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func writeSettings(t *testing.T, path string, packages []string) {
	t.Helper()
	doc := map[string]any{
		"defaultModel": "m",
		"packages":     packages,
	}
	encoded, err := json.Marshal(doc)
	if err != nil {
		t.Fatal(err)
	}
	writeFile(t, path, string(encoded))
}

func readPackages(t *testing.T, path string) []string {
	t.Helper()
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		Packages []string `json:"packages"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	return doc.Packages
}

func TestSetExtensionEnabledStashesRemovedEntry(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	writeSettings(t, s.settingsPath, []string{"a", "b"})

	if err := s.setExtensionEnabled("a", false); err != nil {
		t.Fatal(err)
	}
	if got := readPackages(t, s.settingsPath); len(got) != 1 || got[0] != "b" {
		t.Fatalf("packages after disable = %v", got)
	}
	if stash := s.readStash(); len(stash) != 1 || stash[0] != "a" {
		t.Fatalf("stash after disable = %v", stash)
	}

	if err := s.setExtensionEnabled("a", true); err != nil {
		t.Fatal(err)
	}
	got := readPackages(t, s.settingsPath)
	if len(got) != 2 || got[0] != "b" || got[1] != "a" {
		t.Fatalf("packages after enable = %v", got)
	}
	if stash := s.readStash(); len(stash) != 0 {
		t.Fatalf("stash after enable = %v", stash)
	}
}

func TestSetExtensionEnabledKeepsUnknownSettings(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	writeSettings(t, s.settingsPath, []string{"a"})
	if err := s.setExtensionEnabled("a", false); err != nil {
		t.Fatal(err)
	}
	raw, err := os.ReadFile(s.settingsPath)
	if err != nil {
		t.Fatal(err)
	}
	var doc map[string]any
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	if doc["defaultModel"] != "m" {
		t.Fatalf("unrelated settings were dropped: %v", doc)
	}
}

func TestRemoveExtensionDeletesOnlyInsideExtensionsDir(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	inside := filepath.Join(s.agentDir(), "extensions", "mine")
	if err := os.MkdirAll(inside, 0o700); err != nil {
		t.Fatal(err)
	}
	outside := filepath.Join(home, "checkout")
	if err := os.MkdirAll(outside, 0o700); err != nil {
		t.Fatal(err)
	}
	writeSettings(t, s.settingsPath, []string{"extensions/mine", "../../checkout"})

	if _, err := s.removeExtension("extensions/mine", true); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(inside); !os.IsNotExist(err) {
		t.Fatalf("extension dir should have been deleted, stat err = %v", err)
	}

	if _, err := s.removeExtension("../../checkout", true); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(outside); err != nil {
		t.Fatalf("a source checkout outside pig must survive: %v", err)
	}
	if got := readPackages(t, s.settingsPath); len(got) != 0 {
		t.Fatalf("packages after removals = %v", got)
	}
}

func TestRemoveExtensionRefusesBuiltin(t *testing.T) {
	home := t.TempDir()
	s := testSession(t, home)
	writeSettings(t, s.settingsPath, []string{"pigkit/codegraph"})
	if _, err := s.removeExtension("pigkit/codegraph", false); err == nil {
		t.Fatal("a built-in extension must not be removable")
	}
}

func TestResolveSourcePath(t *testing.T) {
	agent := "/home/u/.pig/agent"
	if got := resolveSourcePath(agent, "extensions/plan"); got != filepath.Join(agent, "extensions/plan") {
		t.Fatalf("relative source = %q", got)
	}
	for _, spec := range []string{"pigkit/x", "npm:foo", "git+https://x", "@scope/pkg"} {
		if got := resolveSourcePath(agent, spec); got != "" {
			t.Fatalf("%s should not resolve to a directory, got %q", spec, got)
		}
	}
}
