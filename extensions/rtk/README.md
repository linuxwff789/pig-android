**English** · [简体中文](README.zh-CN.md)

# rtk extension (Go port)

Rewrites every `bash` tool call into its `rtk` equivalent before the command
runs, so `cat`, `grep`, `git diff`, `find` and friends return compressed output
instead of the whole file. All the rewriting lives in the `rtk` CLI
(`rtk rewrite`), which is Rust; this extension is the ~150-line hook that calls
it and hands the new command back to the host.

This is a port of the TypeScript extension that PiG ships with
(`~/.pig/agent/extensions/rtk/index.ts`, originally shared with Oh My Pi). The
port exists for one reason: with only that one TypeScript extension left, PiG
needed a whole Node.js runtime cell (≈62 MB RSS) just to run a 30-line hook.
Everything else in this setup was already Go, so the port removes Node from the
picture entirely.

## Requirements

- `rtk >= 0.23.0` in `PATH` (0.23.0 is when `rtk rewrite` appeared).
  On Termux the entry point is a small `sh` wrapper that starts the
  glibc-linked Rust binary through `$PREFIX/glibc/lib/ld-linux-aarch64.so.1`,
  because upstream only ships `*-unknown-linux-gnu`.
- No Node.js.

## Behaviour

Matches the TypeScript original:

| Rule | Effect |
| --- | --- |
| tool name | only `bash` calls are considered |
| `command` | must be a non-empty string |
| already rewritten | commands starting with `rtk ` pass through |
| `RTK_DISABLED=1` | no rewriting |
| `rtk --version` | probed once per session; missing or `< 0.23.0` disables rewriting and shows `RTK disabled: …` in the status line |
| `rtk rewrite` | 2 s timeout; pass-through on error, timeout, or an exit code other than 0/3 |
| exit code 0 / 3 | rewrite / advisory rewrite — both replace `input.command` |
| anything unexpected | fail open: the original command runs |

The host applies the edited `input` (the Go SDK sends it back as
`_pigToolCallInput`), so the rewrite is what actually executes — even though the
session transcript records the command the model proposed.

## Install

```bash
pig install ~/dev/pig-android/extensions/rtk      # adds it to settings.packages
```

or add the path to `packages` in `~/.pig/agent/settings.json` by hand:

```json
"../../dev/pig-android/extensions/rtk"
```

## Debugging

`rtk rewrite "<cmd>"` is the source of truth; run it directly to see what a
command would become:

```bash
$ rtk rewrite "cat big.txt"; echo "rc=$?"
rtk read big.txt
rc=3
```
