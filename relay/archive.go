package main

import (
	"errors"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Archiving is a move, not a copy: the file leaves `sessions/` (so the drawer
// stops listing it) but stays inside `~/.pig` (so a backup still contains it,
// and the archive survives an app reinstall). `sessions-archive/` mirrors the
// per-project layout of `sessions/`, which makes restoring a plain move back.

func archiveRoot(sessionsDir string) string {
	return filepath.Join(filepath.Dir(sessionsDir), "sessions-archive")
}

// insideDir returns the path of `abs` relative to `root`, refusing anything
// that escapes it.
func insideDir(root, path string) (string, error) {
	abs, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	rootAbs, err := filepath.Abs(root)
	if err != nil {
		return "", err
	}
	rel, err := filepath.Rel(rootAbs, abs)
	if err != nil {
		return "", err
	}
	if rel == ".." || strings.HasPrefix(rel, ".."+string(os.PathSeparator)) {
		return "", errors.New("path is outside the archive")
	}
	if !strings.HasSuffix(abs, ".jsonl") {
		return "", errors.New("not a session file")
	}
	return rel, nil
}

// archivedPath is where `path` lives once archived.
func archivedPath(sessionsDir, path string) (string, error) {
	if err := validateSessionPath(path, sessionsDir); err != nil {
		return "", err
	}
	rel, err := filepath.Rel(sessionsDir, path)
	if err != nil {
		return "", err
	}
	return filepath.Join(archiveRoot(sessionsDir), rel), nil
}

// validateArchivedPath only accepts files inside the archive.
func validateArchivedPath(path, sessionsDir string) (string, error) {
	if strings.TrimSpace(path) == "" {
		return "", errors.New("missing session path")
	}
	return insideDir(archiveRoot(sessionsDir), path)
}

// archiveSessionFile moves an active session file into the archive.
func archiveSessionFile(path, sessionsDir string) (string, error) {
	dest, err := archivedPath(sessionsDir, path)
	if err != nil {
		return "", err
	}
	if err := os.MkdirAll(filepath.Dir(dest), 0o700); err != nil {
		return "", err
	}
	if err := os.Rename(path, dest); err != nil {
		return "", err
	}
	return dest, nil
}

// restoreArchivedFile moves an archived file back into the sessions tree.
func restoreArchivedFile(path, sessionsDir string) (string, error) {
	rel, err := validateArchivedPath(path, sessionsDir)
	if err != nil {
		return "", err
	}
	dest := filepath.Join(sessionsDir, rel)
	if err := os.MkdirAll(filepath.Dir(dest), 0o700); err != nil {
		return "", err
	}
	if err := os.Rename(path, dest); err != nil {
		return "", err
	}
	return dest, nil
}

// purgeArchivedFile deletes an archived file for good.
func purgeArchivedFile(path, sessionsDir string) error {
	if _, err := validateArchivedPath(path, sessionsDir); err != nil {
		return err
	}
	return os.Remove(path)
}

// listArchived is listSessions for the archive: no cap, oldest first is
// unhelpful here, so newest first like the drawer.
func listArchived(sessionsDir string) []map[string]any {
	root := archiveRoot(sessionsDir)
	projects, err := os.ReadDir(root)
	if err != nil {
		return []map[string]any{}
	}
	type archivedFile struct {
		path string
		info os.FileInfo
	}
	var files []archivedFile
	for _, project := range projects {
		if !project.IsDir() {
			continue
		}
		entries, err := os.ReadDir(filepath.Join(root, project.Name()))
		if err != nil {
			continue
		}
		for _, entry := range entries {
			if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".jsonl") {
				continue
			}
			info, err := entry.Info()
			if err != nil {
				continue
			}
			files = append(files, archivedFile{filepath.Join(root, project.Name(), entry.Name()), info})
		}
	}
	sort.Slice(files, func(i, j int) bool {
		return files[i].info.ModTime().After(files[j].info.ModTime())
	})
	out := make([]map[string]any, 0, len(files))
	for _, file := range files {
		item := readSessionMeta(file.path, file.info)
		// The drawer sorts by `archived` time; the file keeps its own mtime.
		item["archived"] = file.info.ModTime().UnixMilli()
		out = append(out, item)
	}
	return out
}
