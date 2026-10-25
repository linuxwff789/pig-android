package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestEmbeddedPlanFilesAreComplete(t *testing.T) {
	files, hash, err := embeddedPlanFiles()
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"commands.go", "events.go", "extension.go", "policy.go", "steps.go", "go.mod"} {
		if len(files[name]) == 0 {
			t.Fatalf("%s missing from the embedded extension", name)
		}
	}
	if len(hash) != 64 {
		t.Fatalf("hash = %q, want 64 hex chars", hash)
	}
}

func TestExtractEmbeddedPlanSyncsAndCleansUp(t *testing.T) {
	target := filepath.Join(t.TempDir(), "pig-plan")
	if err := os.MkdirAll(target, 0o755); err != nil {
		t.Fatal(err)
	}
	stale := filepath.Join(target, "removed.go")
	if err := os.WriteFile(stale, []byte("package plan"), 0o644); err != nil {
		t.Fatal(err)
	}

	first, err := extractEmbeddedPlan(target)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(target, "commands.go")); err != nil {
		t.Fatalf("commands.go not written: %v", err)
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Fatalf("stale file survived the sync")
	}

	second, err := extractEmbeddedPlan(target)
	if err != nil {
		t.Fatal(err)
	}
	if first != second {
		t.Fatalf("hash changed between syncs: %s vs %s", first, second)
	}

	// A modified file is restored: the marker says the set is current, but a
	// truncated copy must not stay broken.
	data, err := os.ReadFile(filepath.Join(target, "commands.go"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(target, "commands.go"), []byte("broken"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := extractEmbeddedPlan(target); err != nil {
		t.Fatal(err)
	}
	restored, err := os.ReadFile(filepath.Join(target, "commands.go"))
	if err != nil {
		t.Fatal(err)
	}
	if string(restored) != string(data) {
		t.Fatalf("modified file was not restored")
	}
}

func TestConfiguredPlanElsewhere(t *testing.T) {
	agent := "/agent"
	target := "/agent/extensions/plan"
	entries := []string{"pigkit/pi-memory", "../../dev/pig-android/extensions/plan"}
	if got := configuredPlanElsewhere(entries, target, agent); !strings.HasSuffix(got, "extensions/plan") {
		t.Fatalf("configuredPlanElsewhere = %q", got)
	}
	// pig stores a path under the agent directory relative to it.
	if got := configuredPlanElsewhere([]string{"extensions/plan", "pigkit/pi-memory"}, target, agent); got != "" {
		t.Fatalf("own entry counted as a foreign plan extension: %q", got)
	}
}
