package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

// extResource is one row of `pig status --json` resources.items[], decorated
// with the two facts the app needs to decide what it may offer: whether the
// entry ships inside the relay (so it cannot be deleted) and whether its
// directory belongs to pig (so deleting it does not touch a source checkout).
type extResource struct {
	Kind      string `json:"kind"`
	Name      string `json:"name"`
	Path      string `json:"path"`
	Scope     string `json:"scope"`
	Origin    string `json:"origin"`
	Source    string `json:"source"`
	Enabled   bool   `json:"enabled"`
	Health    string `json:"health"`
	Builtin   bool   `json:"builtin"`
	Deletable bool   `json:"deletable"`
}

type pigStatus struct {
	Healthy   bool `json:"healthy"`
	Resources struct {
		Total    int           `json:"total"`
		Enabled  int           `json:"enabled"`
		Disabled int           `json:"disabled"`
		Items    []extResource `json:"items"`
	} `json:"resources"`
	Packages struct {
		Total int `json:"total"`
		User  int `json:"user"`
	} `json:"packages"`
}

const (
	// The status command starts a second pig; caching keeps a screen that the
	// user opens and closes from spawning one per redraw.
	extStatusTTL = 3 * time.Second
	extStatusMax = 20 * time.Second
	// Removed package entries are parked here so "disable" can be undone even
	// though settings.json no longer mentions them.
	extStashName = "android-disabled-packages.json"
)

func (s *session) agentDir() string { return filepath.Dir(s.settingsPath) }

// extensionStatus runs `pig status --json` and decorates the rows. A failure to
// run it is reported to the caller instead of being swallowed: a plugin screen
// that silently shows nothing is worse than one that says why.
func (s *session) extensionStatus(force bool) (pigStatus, error) {
	s.extMu.Lock()
	if !force && !s.extAt.IsZero() && time.Since(s.extAt) < extStatusTTL {
		cached := s.extCached
		s.extMu.Unlock()
		return cached, s.extErr
	}
	s.extMu.Unlock()

	ctx, cancel := context.WithTimeout(context.Background(), extStatusMax)
	defer cancel()
	cmd := exec.CommandContext(ctx, s.pigBin, "status", "--json")
	cmd.Dir = s.cwd
	cmd.Env = append(os.Environ(), "PI_OFFLINE=1")
	out, err := cmd.Output()

	var status pigStatus
	if err == nil {
		err = json.Unmarshal(out, &status)
	}
	if err != nil {
		s.extMu.Lock()
		s.extAt = time.Now()
		s.extErr = err
		s.extCached = pigStatus{}
		s.extMu.Unlock()
		return pigStatus{}, err
	}

	extensionRoot := filepath.Join(s.agentDir(), "extensions")
	extensionRoot = filepath.Clean(extensionRoot) + string(filepath.Separator)
	for index := range status.Resources.Items {
		item := &status.Resources.Items[index]
		item.Builtin = strings.HasPrefix(item.Source, "pigkit/")
		clean := filepath.Clean(item.Path) + string(filepath.Separator)
		item.Deletable = !item.Builtin && strings.HasPrefix(clean, extensionRoot)
	}
	sort.SliceStable(status.Resources.Items, func(i, j int) bool {
		if status.Resources.Items[i].Kind != status.Resources.Items[j].Kind {
			return status.Resources.Items[i].Kind < status.Resources.Items[j].Kind
		}
		return status.Resources.Items[i].Name < status.Resources.Items[j].Name
	})

	s.extMu.Lock()
	s.extAt = time.Now()
	s.extErr = nil
	s.extCached = status
	s.extMu.Unlock()
	return status, nil
}

func (s *session) invalidateExtensions() {
	s.extMu.Lock()
	s.extAt = time.Time{}
	s.extCached = pigStatus{}
	s.extMu.Unlock()
}

// settingsPackages reads the global settings file and the package list inside
// it, keeping every other key as raw JSON so a rewrite cannot drop settings this
// build does not know about.
func (s *session) settingsPackages() (map[string]json.RawMessage, []string, error) {
	raw, err := os.ReadFile(s.settingsPath)
	if err != nil {
		return nil, nil, err
	}
	var doc map[string]json.RawMessage
	if err := json.Unmarshal(raw, &doc); err != nil {
		return nil, nil, err
	}
	var packages []string
	if entry, ok := doc["packages"]; ok {
		_ = json.Unmarshal(entry, &packages)
	}
	return doc, packages, nil
}

func (s *session) writeSettingsPackages(doc map[string]json.RawMessage, packages []string) error {
	encoded, err := json.Marshal(packages)
	if err != nil {
		return err
	}
	if packages == nil {
		delete(doc, "packages")
	} else {
		doc["packages"] = encoded
	}
	out, err := json.MarshalIndent(doc, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(s.settingsPath, append(out, '\n'))
}

func writeFileAtomic(path string, data []byte) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	defer os.Remove(name)
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Chmod(name, 0o600); err != nil {
		return err
	}
	return os.Rename(name, path)
}

func (s *session) stashPath() string { return filepath.Join(s.agentDir(), extStashName) }

func (s *session) readStash() []string {
	raw, err := os.ReadFile(s.stashPath())
	if err != nil {
		return nil
	}
	var doc struct {
		Disabled []string `json:"disabled"`
	}
	if json.Unmarshal(raw, &doc) != nil {
		return nil
	}
	return doc.Disabled
}

func (s *session) writeStash(disabled []string) error {
	sort.Strings(disabled)
	encoded, err := json.MarshalIndent(map[string]any{"disabled": disabled}, "", "  ")
	if err != nil {
		return err
	}
	if len(disabled) == 0 {
		return os.Remove(s.stashPath())
	}
	return writeFileAtomic(s.stashPath(), append(encoded, '\n'))
}

func indexOf(list []string, value string) int {
	for index, entry := range list {
		if entry == value {
			return index
		}
	}
	return -1
}

// withSettingsLock serializes a read-modify-write of settings.json. Callers
// must not call registerPigkitPackages or handleSettings inside fn: the lock is
// not reentrant.
func withSettingsLock(fn func() error) error {
	settingsWriteMu.Lock()
	defer settingsWriteMu.Unlock()
	return fn()
}

// setExtensionEnabled toggles a package entry. It mirrors what `pig config`
// persists: membership in settings.json `packages`.
func (s *session) setExtensionEnabled(source string, enabled bool) error {
	if source == "" {
		return fmt.Errorf("empty source")
	}
	// The settings page (handleSettings) and the embedded-extension installer
	// write the same file. Without this lock one read-modify-write silently
	// replaced the other: a disabled extension came back, or a new setting was
	// lost.
	err := withSettingsLock(func() error {
		doc, packages, err := s.settingsPackages()
		if err != nil {
			return err
		}
		stash := s.readStash()
		if enabled {
			if indexOf(packages, source) >= 0 {
				return nil
			}
			packages = append(packages, source)
			if index := indexOf(stash, source); index >= 0 {
				stash = append(stash[:index], stash[index+1:]...)
			}
		} else {
			index := indexOf(packages, source)
			if index < 0 {
				return nil
			}
			packages = append(packages[:index], packages[index+1:]...)
			if indexOf(stash, source) < 0 {
				stash = append(stash, source)
			}
		}
		if err := s.writeSettingsPackages(doc, packages); err != nil {
			return err
		}
		return s.writeStash(stash)
	})
	if err != nil {
		return err
	}
	s.invalidateExtensions()
	return nil
}

// removeExtension unregisters a package and, when asked, deletes its directory.
// Deletion is limited to pig's own extensions directory: a source checkout that
// happens to be listed must never be removed by a tap in the app.
func (s *session) removeExtension(source string, deleteFiles bool) (string, error) {
	if source == "" {
		return "", fmt.Errorf("empty source")
	}
	if strings.HasPrefix(source, "pigkit/") {
		return "", fmt.Errorf("内置扩展不能卸载，只能停用")
	}
	err := withSettingsLock(func() error {
		doc, packages, err := s.settingsPackages()
		if err != nil {
			return err
		}
		index := indexOf(packages, source)
		if index < 0 {
			return fmt.Errorf("不在 package 列表里")
		}
		packages = append(packages[:index], packages[index+1:]...)
		if err := s.writeSettingsPackages(doc, packages); err != nil {
			return err
		}
		if stash := s.readStash(); indexOf(stash, source) >= 0 {
			_ = s.writeStash(append(stash[:indexOf(stash, source)], stash[indexOf(stash, source)+1:]...))
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	s.invalidateExtensions()

	deleted := ""
	if deleteFiles {
		path := resolveSourcePath(s.agentDir(), source)
		root := filepath.Clean(filepath.Join(s.agentDir(), "extensions")) + string(filepath.Separator)
		clean := filepath.Clean(path) + string(filepath.Separator)
		if path != "" && strings.HasPrefix(clean, root) {
			if err := os.RemoveAll(path); err == nil {
				deleted = path
			}
		}
	}
	return deleted, nil
}

// resolveSourcePath turns a settings entry into an absolute path the way pig
// resolves it: relative entries are relative to the agent directory.
func resolveSourcePath(agentDir, source string) string {
	if source == "" {
		return ""
	}
	if strings.HasPrefix(source, "pigkit/") || strings.HasPrefix(source, "npm:") ||
		strings.HasPrefix(source, "git+") || strings.HasPrefix(source, "@") {
		return ""
	}
	if filepath.IsAbs(source) {
		return source
	}
	return filepath.Join(agentDir, source)
}
