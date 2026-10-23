// Read-only command policy for plan mode.
//
// A plan turn must be able to inspect the machine (that is where the plan comes
// from) without changing it. We cannot trust the model to remember that, so the
// policy runs on every tool_call while plan mode is on.
//
// The rule is "every command in the pipeline must start with a read-only verb".
// That is stricter than a plain denylist (a denylist misses `> file` inside a
// pipeline) and looser than Pi's "the whole line must start with a safe verb",
// which would reject the `cd dir && rg …` shape that this environment uses all
// the time.
package plan

import (
	"regexp"
	"strings"
)

// destructive marks commands that write, delete or install. A hit rejects the
// whole call even when every segment starts with a safe verb.
var destructive = []*regexp.Regexp{
	regexp.MustCompile(`(?i)\brm\b`),
	regexp.MustCompile(`(?i)\brmdir\b`),
	regexp.MustCompile(`(?i)\bmv\b`),
	regexp.MustCompile(`(?i)\bcp\b`),
	regexp.MustCompile(`(?i)\bmkdir\b`),
	regexp.MustCompile(`(?i)\btouch\b`),
	regexp.MustCompile(`(?i)\bchmod\b`),
	regexp.MustCompile(`(?i)\bchown\b`),
	regexp.MustCompile(`(?i)\bchgrp\b`),
	regexp.MustCompile(`(?i)\bln\b`),
	regexp.MustCompile(`(?i)\btee\b`),
	regexp.MustCompile(`(?i)\btruncate\b`),
	regexp.MustCompile(`(?i)\bdd\b`),
	regexp.MustCompile(`(?i)\bshred\b`),
	regexp.MustCompile(`(?i)\bsed\b[^|;&]*\s-\w*i`),
	regexp.MustCompile(`(?i)\b(npm|yarn|pnpm)\s+(install|uninstall|update|add|remove|ci|link|publish)`),
	regexp.MustCompile(`(?i)\b(pip|pip3)\s+(install|uninstall)`),
	regexp.MustCompile(`(?i)\b(apt|apt-get|pkg)\s+(install|remove|purge|update|upgrade)`),
	regexp.MustCompile(`(?i)\b(brew|port)\s+(install|uninstall|upgrade)`),
	regexp.MustCompile(`(?i)\bgit\s+(add|commit|push|pull|merge|rebase|reset|checkout|switch|restore|clean|stash|cherry-pick|revert|tag|init|clone|am|apply)`),
	regexp.MustCompile(`(?i)\b(sudo|su)\b`),
	regexp.MustCompile(`(?i)\b(kill|pkill|killall)\b`),
	regexp.MustCompile(`(?i)\b(reboot|shutdown|poweroff)\b`),
	regexp.MustCompile(`(?i)\bsystemctl\s+(start|stop|restart|enable|disable)`),
	regexp.MustCompile(`(?i)\b(vim?|nvim|nano|emacs|code|subl|ed)\b`),
	regexp.MustCompile(`(?i)\bgo\s+(build|install|run|generate|mod\s+(tidy|download))`),
	regexp.MustCompile(`(?i)\bpig\s+(install|update|reload|piglet)\b`),
	regexp.MustCompile(`(?i)\b(termux-wake-lock|termux-wake-unlock|am|pm|svc|settings)\b`),
}

// safeVerbs is the first word of a segment that only reads.
var safeVerbs = map[string]bool{
	"cat": true, "head": true, "tail": true, "less": true, "more": true,
	"grep": true, "egrep": true, "rg": true, "ag": true, "ack": true,
	"find": true, "fd": true, "fdfind": true, "ls": true, "ll": true, "la": true,
	"pwd": true, "echo": true, "printf": true, "wc": true, "sort": true,
	"uniq": true, "diff": true, "cmp": true, "file": true, "stat": true,
	"du": true, "df": true, "tree": true, "which": true, "whereis": true,
	"type": true, "env": true, "printenv": true, "uname": true, "whoami": true,
	"id": true, "date": true, "cal": true, "uptime": true, "ps": true,
	"top": true, "htop": true, "free": true, "jq": true, "awk": true,
	"sed": true, "bat": true, "eza": true, "exa": true, "xxd": true,
	"od": true, "strings": true, "sha256sum": true, "md5sum": true,
	"cd": true, "test": true, "[": true,
	"codegraph": true, "node": true, "python": true, "python3": true,
	"git": true, "curl": true, "wget": true, "pig": true,
}

// segments splits a shell line on the operators that start a new command.
// Quoted strings are not special-cased: a pipe inside quotes is rare and
// treating it as a separator only makes the check stricter.
func segments(command string) []string {
	replacer := strings.NewReplacer("&&", "\n", "||", "\n", ";", "\n", "|", "\n")
	return strings.Split(replacer.Replace(command), "\n")
}

// SafeCommand reports whether command may run in plan mode.
func SafeCommand(command string) bool {
	command = strings.TrimSpace(command)
	if command == "" {
		return false
	}
	// Redirection writes a file no matter which verb produced the text.
	if strings.Contains(command, ">") {
		return false
	}
	for _, pattern := range destructive {
		if pattern.MatchString(command) {
			return false
		}
	}
	for _, segment := range segments(command) {
		segment = strings.TrimSpace(segment)
		if segment == "" {
			continue
		}
		segment = stripEnv(segment)
		fields := strings.Fields(segment)
		if len(fields) == 0 {
			continue
		}
		verb := fields[0]
		if strings.Contains(verb, "=") {
			verb = "" // leading VAR=value assignment
		}
		if verb != "" && !safeVerbs[verb] {
			return false
		}
		switch verb {
		case "sed":
			if !hasFlag(fields, "-n") {
				return false
			}
		case "git":
			if len(fields) < 2 || !safeGit[fields[1]] {
				return false
			}
		case "node", "python", "python3":
			// Interpreting anything is a write vector; only version probes.
			if len(fields) < 2 || (fields[1] != "--version" && fields[1] != "-V") {
				return false
			}
		case "pig":
			if len(fields) < 2 || fields[1] != "--list-models" {
				return false
			}
		}
	}
	return true
}

var safeGit = map[string]bool{
	"status": true, "log": true, "diff": true, "show": true, "branch": true,
	"remote": true, "rev-parse": true, "describe": true, "blame": true,
	"grep": true, "ls-files": true, "ls-tree": true, "cat-file": true,
	"shortlog": true, "whatchanged": true, "config": true, "tag": true,
}

// stripEnv drops leading VAR=value assignments so `FOO=1 rg x` reads as `rg x`.
func stripEnv(segment string) string {
	fields := strings.Fields(segment)
	for len(fields) > 0 && strings.Contains(fields[0], "=") && !strings.HasPrefix(fields[0], "-") {
		fields = fields[1:]
	}
	return strings.Join(fields, " ")
}

func hasFlag(fields []string, want string) bool {
	for _, field := range fields[1:] {
		if field == want || (strings.HasPrefix(field, "-") && !strings.HasPrefix(field, "--") && strings.Contains(field, "n")) {
			return true
		}
	}
	return false
}
