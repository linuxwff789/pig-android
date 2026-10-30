package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestWriteSettingsFailureBranches(t *testing.T) {
	dir := t.TempDir()
	// Rename onto an existing directory fails after the temp file was written.
	asDir := filepath.Join(dir, "settings.json")
	if err := os.Mkdir(asDir, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := writeSettingsFile(asDir, map[string]interface{}{"a": 1}); err == nil {
		t.Fatal("writeSettingsFile replaced a directory")
	}

	if os.Geteuid() != 0 {
		locked := filepath.Join(dir, "locked")
		if err := os.Mkdir(locked, 0o700); err != nil {
			t.Fatal(err)
		}
		path := filepath.Join(locked, "settings.json")
		if err := os.WriteFile(path, []byte("{}\n"), 0o600); err != nil {
			t.Fatal(err)
		}
		if err := os.Chmod(locked, 0o500); err != nil {
			t.Fatal(err)
		}
		defer func() { _ = os.Chmod(locked, 0o700) }()
		// Reading works, writing the pre-app backup does not.
		if err := writeSettingsFile(path, map[string]interface{}{"a": 1}); err == nil {
			t.Fatal("writeSettingsFile ignored a failed backup")
		}
	}
}

func TestWriteAutoBackupErrors(t *testing.T) {
	s := newTestSession(t)
	if err := os.MkdirAll(filepath.Join(s.cwd, ".pig"), 0o700); err != nil {
		t.Fatal(err)
	}
	// A file where the backup directory should be makes MkdirAll fail.
	if err := os.WriteFile(filepath.Join(s.cwd, ".pig", "backups"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := s.writeAutoBackup(); err == nil {
		t.Fatal("writeAutoBackup ignored the directory error")
	}
}

func TestInstallEmbeddedPigkitFailureBranches(t *testing.T) {
	dir := t.TempDir()
	s := &session{settingsPath: filepath.Join(dir, "settings.json")}

	// The agent path is a file, so pigkit/ cannot be created.
	blocked := filepath.Join(dir, "blocked-agent")
	if err := os.WriteFile(blocked, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	s.installEmbeddedPigkit(blocked)

	// A broken settings.json is reported after extraction.
	agent := filepath.Join(dir, "agent")
	if err := os.MkdirAll(agent, 0o700); err != nil {
		t.Fatal(err)
	}
	s.settingsPath = filepath.Join(agent, "settings.json")
	if err := os.WriteFile(s.settingsPath, []byte("{bad"), 0o600); err != nil {
		t.Fatal(err)
	}
	s.installEmbeddedPigkit(agent)
	if _, err := os.Stat(filepath.Join(agent, "pigkit", "provider-ui", embeddedPigkitHash)); err != nil {
		t.Fatalf("pigkit sources were not extracted: %v", err)
	}
}

func TestDeleteSessionEmptyPath(t *testing.T) {
	dir := t.TempDir()
	if err := deleteSession("", dir); err == nil {
		t.Fatal("empty session path accepted")
	}
}
