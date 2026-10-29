package main

import (
	"crypto/sha256"
	"embed"
	"encoding/hex"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// The pigkit module holds the user-facing extensions behind the app's own UI:
// the provider window (`/provider-ui`), custom endpoints, model speed, memory,
// the questionnaire and codegraph. It ships inside the relay for the same
// reason `plan` does — a fresh Termux must not end up with a backend that only
// knows built-in commands — and is written to <agent>/pigkit, which is where
// the `pigkit/<name>` package entries resolve.
//
//go:embed embedded/pigkit
var embeddedPigkit embed.FS

const (
	embeddedPigkitDir = "embedded/pigkit"
	// Marker per package directory. Without it the directory belongs to the
	// user (hand-written, edited, checked out somewhere) and is never touched.
	embeddedPigkitHash = ".embedded-hash"
	pigkitModuleFile   = "go.mod"
)

// embeddedPigkitFiles splits the embedded tree into the module file and one
// file set per package (paths keep their subdirectory, e.g. "provider-ui/
// window.go").
func embeddedPigkitFiles() (map[string][]byte, map[string]map[string][]byte, error) {
	root := map[string][]byte{}
	pkgs := map[string]map[string][]byte{}
	err := fs.WalkDir(embeddedPigkit, embeddedPigkitDir, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if entry.IsDir() {
			return nil
		}
		rel, err := filepath.Rel(embeddedPigkitDir, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		data, err := embeddedPigkit.ReadFile(path)
		if err != nil {
			return err
		}
		name := strings.TrimSuffix(rel, ".txt")
		if slash := strings.Index(name, "/"); slash >= 0 {
			pkg, rest := name[:slash], name[slash+1:]
			if pkgs[pkg] == nil {
				pkgs[pkg] = map[string][]byte{}
			}
			pkgs[pkg][rest] = data
			return nil
		}
		root[name] = data
		return nil
	})
	return root, pkgs, err
}

// pigkitHash hashes one file set (name + content) so the marker can tell an
// up-to-date copy from a stale one.
func pigkitHash(files map[string][]byte) string {
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	sum := sha256.New()
	for _, name := range names {
		fmt.Fprintf(sum, "%s\x00", name)
		sum.Write(files[name])
	}
	return hex.EncodeToString(sum.Sum(nil))
}

// installEmbeddedPigkit writes the embedded packages under <agent>/pigkit and
// makes sure every one of them is listed in settings.json.
func (s *session) installEmbeddedPigkit(agentDir string) {
	root, pkgs, err := embeddedPigkitFiles()
	if err != nil {
		logf("embedded pigkit: %v", err)
		return
	}
	if len(pkgs) == 0 {
		return
	}
	target := filepath.Join(agentDir, "pigkit")
	if err := os.MkdirAll(target, 0o755); err != nil {
		logf("embedded pigkit: %v", err)
		return
	}

	// The module file is what makes `pigkit/<name>` a package instead of a
	// standalone directory. Write it when it is missing, or when the root
	// marker says we wrote it; a go.mod the user wrote stays as it is.
	modulePath := filepath.Join(target, pigkitModuleFile)
	rootMarker := filepath.Join(target, embeddedPigkitHash)
	if data := root[pigkitModuleFile]; data != nil {
		current, readErr := os.ReadFile(modulePath)
		_, markerErr := os.Stat(rootMarker)
		switch {
		case os.IsNotExist(readErr):
			if err := os.WriteFile(modulePath, data, 0o644); err != nil {
				logf("embedded pigkit: %v", err)
				return
			}
			_ = os.WriteFile(rootMarker, []byte(pigkitHash(root)+"\n"), 0o644)
		case readErr == nil && markerErr == nil && string(current) != string(data):
			if err := os.WriteFile(modulePath, data, 0o644); err != nil {
				logf("embedded pigkit: %v", err)
			} else {
				_ = os.WriteFile(rootMarker, []byte(pigkitHash(root)+"\n"), 0o644)
			}
		}
	}

	names := make([]string, 0, len(pkgs))
	for name := range pkgs {
		names = append(names, name)
	}
	sort.Strings(names)

	managed := make([]string, 0, len(names))
	for _, pkg := range names {
		dir := filepath.Join(target, pkg)
		marker := filepath.Join(dir, embeddedPigkitHash)
		_, markerErr := os.Stat(marker)
		if _, statErr := os.Stat(dir); statErr == nil && markerErr != nil {
			logf("pigkit/%s exists and was not written by the relay; leaving it alone", pkg)
			continue
		}
		if err := os.MkdirAll(dir, 0o755); err != nil {
			logf("embedded pigkit %s: %v", pkg, err)
			continue
		}
		// Marker first: an interrupted sync must be retried on the next start,
		// not mistaken for a hand-written package and skipped forever.
		hash := pigkitHash(pkgs[pkg])
		if err := os.WriteFile(marker, []byte(hash+"\n"), 0o644); err != nil {
			logf("embedded pigkit %s: %v", pkg, err)
			continue
		}
		if _, err := syncPigkitPackage(dir, pkgs[pkg]); err != nil {
			logf("embedded pigkit %s: %v", pkg, err)
			continue
		}
		managed = append(managed, pkg)
	}
	if len(managed) == 0 {
		return
	}
	if err := s.registerPigkitPackages(agentDir, target, managed); err != nil {
		logf("embedded pigkit not registered: %v", err)
		return
	}
	logf("embedded pigkit extensions ready: %s", strings.Join(managed, ", "))
}

// syncPigkitPackage rewrites only the files whose content differs and drops
// files an older relay left behind, so pig's build cache stays warm.
func syncPigkitPackage(dir string, files map[string][]byte) (string, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	// Recursive: a stale subdirectory left by an older relay must not make
	// `pig install` compile sources the relay no longer ships.
	pruneTree(dir, files, embeddedPigkitHash)
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	for _, name := range names {
		path := filepath.Join(dir, name)
		if current, err := os.ReadFile(path); err == nil && string(current) == string(files[name]) {
			continue
		}
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			return "", err
		}
		if err := os.WriteFile(path, files[name], 0o644); err != nil {
			return "", err
		}
	}
	return pigkitHash(files), nil
}

// registerPigkitPackages appends `pigkit/<name>` to settings.json for every
// package the relay manages that nothing else already provides. A user entry
// pointing at the same directory counts; an entry with the same package name
// somewhere else wins, because loading both would register the commands twice.
func (s *session) registerPigkitPackages(agentDir, target string, pkgs []string) error {
	settingsWriteMu.Lock()
	defer settingsWriteMu.Unlock()
	settings, err := readSettingsFile(s.settingsPath)
	if err != nil {
		return err
	}
	entries := stringList(settings["packages"])
	changed := false
	for _, pkg := range pkgs {
		dir := filepath.Join(target, pkg)
		provided, by := packageProvided(entries, dir, agentDir, pkg)
		if provided {
			if !samePath(resolvePackageEntry(by, agentDir), dir) {
				logf("pigkit/%s is already provided by %s; not registering it twice", pkg, by)
			}
			continue
		}
		entries = append(entries, "pigkit/"+pkg)
		changed = true
	}
	if !changed {
		return nil
	}
	asAny := make([]interface{}, 0, len(entries))
	for _, entry := range entries {
		asAny = append(asAny, entry)
	}
	settings["packages"] = asAny
	return writeSettingsFile(s.settingsPath, settings)
}

// packageProvided reports whether entries already provide the package at dir,
// and which entry did it. A path that resolves to dir counts, and so does an
// entry that names the package from somewhere else (a checkout, an npm twin):
// registering both would load the same commands twice.
func packageProvided(entries []string, dir, agentDir, pkg string) (bool, string) {
	for _, entry := range entries {
		if samePath(resolvePackageEntry(entry, agentDir), dir) {
			return true, entry
		}
		name := strings.TrimPrefix(entry, "npm:")
		name = strings.TrimSuffix(strings.TrimRight(name, "/"), ".git")
		if filepath.Base(filepath.Clean(name)) == pkg {
			return true, entry
		}
	}
	return false, ""
}

// resolvePackageEntry turns a settings `packages` entry into an absolute path.
func resolvePackageEntry(entry, agentDir string) string {
	if filepath.IsAbs(entry) {
		return filepath.Clean(entry)
	}
	return filepath.Clean(filepath.Join(agentDir, entry))
}

func samePath(left, right string) bool {
	return filepath.Clean(left) == filepath.Clean(right)
}

// stringList converts the `packages` array from settings.json, dropping
// anything that is not a non-empty string.
func stringList(value interface{}) []string {
	raw, ok := value.([]interface{})
	if !ok {
		if direct, ok := value.([]string); ok {
			return direct
		}
		return nil
	}
	out := make([]string, 0, len(raw))
	for _, item := range raw {
		text, ok := item.(string)
		if !ok {
			continue
		}
		if text = strings.TrimSpace(text); text != "" {
			out = append(out, text)
		}
	}
	return out
}
