// Embedded extensions ship inside the relay binary, so installing or updating
// the relay also installs the extension it belongs to. On first start the
// sources are written under <agent>/extensions/ and registered with
// `pig install`; a user-configured extension of the same name always wins.
package main

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

//go:embed embedded/plan/*
var embeddedPlan embed.FS

const (
	embeddedPlanDir = "embedded/plan"
	// pig matches an extension's directory name against its registered identity,
	// so the extracted copy has to be called "plan".
	embeddedPlanName  = "plan"
	embeddedPlanHash  = ".embedded-hash"
	embeddedInstallTO = 5 * time.Minute
)

// installEmbeddedExtensions writes and registers the extensions the relay
// carries. It runs once per relay start, in the background; startAgent waits on
// session.extReady so the first pig already sees the extension.
func (s *session) installEmbeddedExtensions() {
	defer close(s.extReady)

	agentDir := filepath.Dir(s.settingsPath)
	// The pigkit module first: the app's own buttons (`ext:` actions in the UI
	// pack) call its commands, and a backend without it silently answers with
	// built-ins only. Its `packages` entries are written before plan reads them.
	s.installEmbeddedPigkit(agentDir)
	s.installEmbeddedPlan(agentDir)
}

// installEmbeddedPlan writes the plan extension the relay carries into
// <agent>/extensions/plan and registers it with `pig install`.
func (s *session) installEmbeddedPlan(agentDir string) {
	entries := settingsPackages(s.settingsPath)
	target := filepath.Join(agentDir, "extensions", embeddedPlanName)

	// A plan extension the user configured (this repo's checkout, a package, a
	// hand-written copy) must not be loaded twice: same commands, same widget
	// key. Leave it alone.
	if other := configuredPlanElsewhere(entries, target, agentDir); other != "" {
		logf("plan extension already configured at %s; embedded copy skipped", other)
		return
	}
	// The folder is pig's; only manage it when it is ours (the marker) or when
	// nobody put anything there.
	if _, err := os.Stat(target); err == nil {
		if _, markerErr := os.Stat(filepath.Join(target, embeddedPlanHash)); markerErr != nil {
			logf("%s exists and was not written by the relay; leaving it alone", target)
			return
		}
	}

	hash, err := extractEmbeddedPlan(target)
	if err != nil {
		logf("embedded plan extension: %v", err)
		return
	}
	for _, entry := range entries {
		resolved := entry
		if !filepath.IsAbs(resolved) {
			resolved = filepath.Join(agentDir, resolved)
		}
		if filepath.Clean(resolved) == filepath.Clean(target) {
			return
		}
	}
	if err := runPigInstall(s.pigBin, target); err != nil {
		logf("embedded plan extension not registered: %v", err)
		logf("hint: Go extensions are built with the system Go toolchain (Termux: pkg install golang); the relay retries on its next start")
		return
	}
	logf("embedded plan extension installed at %s (%s)", target, hash[:8])
}

// configuredPlanElsewhere reports the package entry that already provides a plan
// extension, if any. The entry the relay manages itself does not count.
func configuredPlanElsewhere(entries []string, target, agentDir string) string {
	for _, entry := range entries {
		resolved := entry
		if !filepath.IsAbs(resolved) {
			resolved = filepath.Join(agentDir, resolved)
		}
		if filepath.Clean(resolved) == filepath.Clean(target) {
			continue
		}
		if filepath.Base(filepath.Clean(entry)) == "plan" {
			return entry
		}
	}
	return ""
}

// extractEmbeddedPlan syncs the embedded sources into target and returns their
// hash. Files are rewritten only when the content changed, and files an older
// relay left behind are removed, so pig's build cache is not invalidated for
// nothing and a removed file cannot linger.
func extractEmbeddedPlan(target string) (string, error) {
	files, hash, err := embeddedPlanFiles()
	if err != nil {
		return "", err
	}
	// Compare the files, not just the marker: a truncated copy or a file a newer
	// relay removed is repaired on the next start instead of failing the build.
	if embeddedPlanMatches(target, files) {
		_ = os.WriteFile(filepath.Join(target, embeddedPlanHash), []byte(hash+"\n"), 0o644)
		return hash, nil
	}
	if err := os.MkdirAll(target, 0o755); err != nil {
		return "", err
	}
	existing, _ := os.ReadDir(target)
	for _, entry := range existing {
		if entry.IsDir() || entry.Name() == embeddedPlanHash {
			continue
		}
		if _, ok := files[entry.Name()]; !ok {
			_ = os.Remove(filepath.Join(target, entry.Name()))
		}
	}
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		path := filepath.Join(target, name)
		if current, err := os.ReadFile(path); err == nil && string(current) == string(files[name]) {
			continue
		}
		if err := os.WriteFile(path, files[name], 0o644); err != nil {
			return "", err
		}
	}
	if err := os.WriteFile(filepath.Join(target, embeddedPlanHash), []byte(hash+"\n"), 0o644); err != nil {
		return "", err
	}
	return hash, nil
}

// embeddedPlanMatches reports whether target already holds exactly the embedded
// file set.
func embeddedPlanMatches(target string, files map[string][]byte) bool {
	entries, err := os.ReadDir(target)
	if err != nil {
		return false
	}
	seen := 0
	for _, entry := range entries {
		if entry.IsDir() || entry.Name() == embeddedPlanHash {
			continue
		}
		want, ok := files[entry.Name()]
		if !ok {
			return false
		}
		got, err := os.ReadFile(filepath.Join(target, entry.Name()))
		if err != nil || string(got) != string(want) {
			return false
		}
		seen++
	}
	return seen == len(files)
}

// embeddedPlanFiles returns the extracted file set (name -> content, .txt
// suffix dropped) and a hash over the whole set.
func embeddedPlanFiles() (map[string][]byte, string, error) {
	entries, err := embeddedPlan.ReadDir(embeddedPlanDir)
	if err != nil {
		return nil, "", err
	}
	files := make(map[string][]byte, len(entries))
	names := make([]string, 0, len(entries))
	contents := make(map[string][]byte, len(entries))
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		data, err := embeddedPlan.ReadFile(embeddedPlanDir + "/" + entry.Name())
		if err != nil {
			return nil, "", err
		}
		name := strings.TrimSuffix(entry.Name(), ".txt")
		files[name] = data
		names = append(names, name)
		contents[name] = data
	}
	sort.Strings(names)
	sum := sha256.New()
	for _, name := range names {
		fmt.Fprintf(sum, "%s\x00", name)
		sum.Write(contents[name])
	}
	return files, hex.EncodeToString(sum.Sum(nil)), nil
}

// settingsPackages reads the `packages` list of the global settings file.
func settingsPackages(path string) []string {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil
	}
	var settings struct {
		Packages []string `json:"packages"`
	}
	if json.Unmarshal(data, &settings) != nil {
		return nil
	}
	out := make([]string, 0, len(settings.Packages))
	for _, entry := range settings.Packages {
		if entry = strings.TrimSpace(entry); entry != "" {
			out = append(out, entry)
		}
	}
	return out
}

// runPigInstall registers the extracted extension with pig. It also builds it,
// so a broken copy is reported here instead of failing the next session.
func runPigInstall(pigBin, dir string) error {
	cmd := exec.Command(pigBin, "install", dir)
	cmd.Dir = filepath.Dir(dir)
	out, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}

// logf writes a relay line to stderr *and* hands it to the connected app. The
// user only ever sees the app, and "the relay said why" is the difference
// between an actionable error and a spinner.
func logf(format string, args ...any) {
	message := fmt.Sprintf(format, args...)
	fmt.Fprintf(os.Stderr, "pig-relay: %s\n", message)
	if sink := relayLogSink; sink != nil {
		sink("relay", logLevel(message), message)
	}
}

// relayLogSink is set once, by the single session created in main. Lines
// logged before that (or after it is gone) only reach stderr.
var relayLogSink func(source, level, message string)
