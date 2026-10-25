package main

import (
	"os"
	"path/filepath"
	"slices"
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

func TestEmbeddedPigkitFilesAreComplete(t *testing.T) {
	root, pkgs, err := embeddedPigkitFiles()
	if err != nil {
		t.Fatal(err)
	}
	if len(root[pigkitModuleFile]) == 0 {
		t.Fatalf("%s missing from the embedded pigkit module", pigkitModuleFile)
	}
	for _, name := range []string{
		"codegraph", "custom-endpoints", "model-speed", "pi-memory",
		"pi-permission-system", "provider-ui", "questionnaire", "reload-runtime",
	} {
		if len(pkgs[name]) == 0 {
			t.Fatalf("pigkit/%s missing from the embedded module", name)
		}
	}
	if len(pkgs["provider-ui"]["window.go"]) == 0 {
		t.Fatalf("provider-ui window.go missing")
	}
}

func TestInstallEmbeddedPigkitExtractsAndRegisters(t *testing.T) {
	agent := filepath.Join(t.TempDir(), "agent")
	if err := os.MkdirAll(agent, 0o755); err != nil {
		t.Fatal(err)
	}
	settings := filepath.Join(agent, "settings.json")
	if err := os.WriteFile(settings, []byte(`{"packages":["../../dev/pig-android/extensions/plan"]}`), 0o644); err != nil {
		t.Fatal(err)
	}
	s := &session{settingsPath: settings}

	s.installEmbeddedPigkit(agent)

	for _, pkg := range []string{"provider-ui", "custom-endpoints", "reload-runtime"} {
		dir := filepath.Join(agent, "pigkit", pkg)
		if _, err := os.Stat(filepath.Join(dir, embeddedPigkitHash)); err != nil {
			t.Fatalf("pigkit/%s marker missing: %v", pkg, err)
		}
	}
	if _, err := os.Stat(filepath.Join(agent, "pigkit", pigkitModuleFile)); err != nil {
		t.Fatalf("%s not written: %v", pigkitModuleFile, err)
	}

	entries, err := readSettingsFile(settings)
	if err != nil {
		t.Fatal(err)
	}
	packages := stringList(entries["packages"])
	if len(packages) != 9 {
		t.Fatalf("packages = %v, want the plan entry plus 8 pigkit ones", packages)
	}
	if packages[0] != "../../dev/pig-android/extensions/plan" {
		t.Fatalf("existing entry was not preserved: %v", packages)
	}
	want := "pigkit/provider-ui"
	if !slices.Contains(packages, want) {
		t.Fatalf("%s not registered: %v", want, packages)
	}

	// Idempotent: a second start adds nothing and keeps the file set current.
	s.installEmbeddedPigkit(agent)
	entries, err = readSettingsFile(settings)
	if err != nil {
		t.Fatal(err)
	}
	if got := stringList(entries["packages"]); len(got) != len(packages) {
		t.Fatalf("second run duplicated entries: %v", got)
	}
}

func TestInstallEmbeddedPigkitLeavesUserCopyAlone(t *testing.T) {
	agent := filepath.Join(t.TempDir(), "agent")
	userDir := filepath.Join(agent, "pigkit", "pi-memory")
	if err := os.MkdirAll(userDir, 0o755); err != nil {
		t.Fatal(err)
	}
	own := []byte("package memory // hand written")
	if err := os.WriteFile(filepath.Join(userDir, "extension.go"), own, 0o644); err != nil {
		t.Fatal(err)
	}
	s := &session{settingsPath: filepath.Join(agent, "settings.json")}

	s.installEmbeddedPigkit(agent)

	got, err := os.ReadFile(filepath.Join(userDir, "extension.go"))
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != string(own) {
		t.Fatalf("user copy was overwritten")
	}
	entries, _ := readSettingsFile(s.settingsPath)
	for _, entry := range stringList(entries["packages"]) {
		if entry == "pigkit/pi-memory" {
			t.Fatalf("user copy registered as the relay's: %v", entry)
		}
	}
}

func TestPackageProvidedMatchesByName(t *testing.T) {
	agent := "/agent"
	if ok, _ := packageProvided([]string{"pigkit/provider-ui"}, "/agent/pigkit/provider-ui", agent, "provider-ui"); !ok {
		t.Fatalf("same directory not detected")
	}
	if ok, by := packageProvided([]string{"npm:provider-ui"}, "/agent/pigkit/provider-ui", agent, "provider-ui"); !ok || by != "npm:provider-ui" {
		t.Fatalf("npm twin not detected: %v %q", ok, by)
	}
	if ok, _ := packageProvided([]string{"pigkit/reload-runtime"}, "/agent/pigkit/provider-ui", agent, "provider-ui"); ok {
		t.Fatalf("unrelated entry counted as provider-ui")
	}
}
