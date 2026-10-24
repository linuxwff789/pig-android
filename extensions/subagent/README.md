# subagent extension (Go)

A `subagent` tool: the model delegates one self-contained task to a **child
agent session** with its own context window, and gets the child's answer back.

Ported from the `subagent` tool of [`pi-subagent-feather`](https://www.npmjs.com/package/pi-subagent-feather)
(MIT, itself derived from `@eggmasonvalue/pi-subagent` 2.0.1): same parameters,
same receipt shape, same "parallel calls are independent" contract. The
execution model is PiG's, not pi's:

| | pi-subagent-feather | this port |
| --- | --- | --- |
| child runtime | spawns another `pi` process | `ctx.CreateAgentSession` (in-process, host-side) |
| cost | +1 process (~60–250 MB on a phone) | +0 processes, shares the provider runtime |
| child transcript | JSONL file next to the parent's | in-memory (`InMemory: true`), nothing on disk |
| `resume` | yes (child session path) | no — nothing is persisted to resume |

## Tool

```
subagent(
  task      string   required   everything the child needs; it cannot see this conversation
  label     string   optional   short name shown in the receipt (default "child")
  model     string   optional   "provider/id" or plain id; default inherits the session model
  thinking  string   optional   off|minimal|low|medium|high|xhigh|max
  tools     []string optional   allowlist, e.g. ["read","bash"] for read-only scouting;
                                omit = inherit all except `subagent`; [] = no tools
  cwd       string   optional   child working directory (default: the session's)
  timeoutMs int      optional   stop after N ms (default 10 min, max 30 min), returns the partial answer
)
```

Result:

```
done · scout-auth · 2 turns · ↑13707 ↓74 tok · 1 tools · 4s
<child's final answer, truncated to 12000 chars with head+tail kept>
```

Status is `done`, `timeout`, `failed` or `empty`.

## Delegation rules

- The child never inherits `subagent`, so a delegation cannot fork by accident.
  Naming it in `tools` opts in (nesting is allowed on purpose).
- The child inherits the session's model, thinking level, resources and context
  files unless `model` / `thinking` / `cwd` say otherwise.
- The child's first prompt carries the whole system prompt, so a delegation
  costs ~10–15k input tokens even for a tiny task; the parent only pays for the
  receipt and the answer. That is the point: the *investigation* stays in the
  child's context instead of this one.
- Parallel: several `subagent` calls in one turn run as independent child
  sessions.

## Configuration

| Env | Effect |
| --- | --- |
| `PIG_SUBAGENT=off` | the tool stays registered but every call returns "disabled" |

## Install

```bash
pig install ~/dev/pig-android/extensions/subagent
```

## Notes

- `ctx.BindExtensions()` runs so the child's resources can start headless.
- Tokens in the receipt are the child's billable input/output, summed from its
  assistant messages; the child's cache reads are not subtracted.
- `PIG_SUBAGENT=off` is read per call, so a `/reload` is not needed to flip it.
