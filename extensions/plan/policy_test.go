package plan

import "testing"

func TestSafeCommandAllowsReads(t *testing.T) {
	allowed := []string{
		"ls -la",
		"cat README.md",
		"rg -n plan .",
		"cd ~/dev/pig-android && rg -n TODO app",
		"git status",
		"git log --oneline -5",
		"cat a.txt | grep x | head -3",
		"FOO=1 rg x",
		"sed -n '1,20p' file",
		"find . -name '*.kt'",
	}
	for _, command := range allowed {
		if !SafeCommand(command) {
			t.Errorf("expected allowed: %q", command)
		}
	}
}

func TestSafeCommandBlocksWrites(t *testing.T) {
	blocked := []string{
		"rm -rf build",
		"echo hi > file",
		"cat a >> b",
		"cat a | tee b",
		"sed -i 's/a/b/' file",
		"git commit -m x",
		"git push",
		"pkg install go",
		"go build ./...",
		"mkdir -p x",
		"cp a b",
		"mv a b",
		"sudo ls",
		"npm install",
		"cat a | rm -f b",
	}
	for _, command := range blocked {
		if SafeCommand(command) {
			t.Errorf("expected blocked: %q", command)
		}
	}
}

func TestExtractAndMark(t *testing.T) {
	text := "Some prose.\n\nPlan:\n1. Read `agents.go`\n2. **Add** relay_restart\n3. Write the docs.\n\nDone."
	steps := extractSteps(text)
	if len(steps) != 3 {
		t.Fatalf("want 3 steps, got %d (%+v)", len(steps), steps)
	}
	if steps[1].Text != "Add relay_restart" {
		t.Errorf("markdown not cleaned: %q", steps[1].Text)
	}
	if steps[2].Text != "Write the docs" {
		t.Errorf("trailing punctuation kept: %q", steps[2].Text)
	}
	done := doneSteps("did 1 [DONE:1] and [DONE:3] then [DONE:3] again")
	if len(done) != 2 || done[0] != 1 || done[1] != 3 {
		t.Errorf("unexpected done list: %v", done)
	}
}

func TestExtractNeedsHeader(t *testing.T) {
	if steps := extractSteps("1. no header\n2. here"); steps != nil {
		t.Errorf("expected nil without a Plan: header, got %+v", steps)
	}
}
