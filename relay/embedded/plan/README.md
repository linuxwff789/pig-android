**English** · [简体中文](README.zh-CN.md)

# plan — optional plan-mode extension

A read-only planning mode for PiG. The model investigates, writes a numbered
plan, and cannot change anything until you let it. Then it works through the
steps and ticks them off.

This package is **optional**. The Android app does not depend on it: any
extension that emits the [plan widget contract](https://github.com/linuxwff789/pig-android/blob/main/docs/plan-widget.md) gets
the same native card, so you can use this one, a port of Pi's `plan-mode`, or
your own.

## Install

`pig-relay` carries this extension. On its first start (and after every update
that changes it) the relay writes the sources to
`~/.pig/agent/extensions/plan/` and runs `pig install` for it, so installing or
updating the relay installs the extension too — nothing to download separately.
A plan extension you configured yourself always wins: if `settings.json` lists
one (for example this repository's checkout), the relay leaves everything
alone, so the extension is never loaded twice.

To install by hand (for hacking on it):

```bash
git clone https://github.com/linuxwff789/pig-android
pig install ./pig-android/extensions/plan
```

Restart the running agent so the command shows up. In the Android app:
**设置 → 会话行为 → 重启 pig** (it waits for a running turn to finish).
`/reload-runtime` is not enough: it rebuilds the extensions it already resolved
at startup and does not pick up a package added to `settings.json` afterwards
(verified against the current PiG).

Uninstall: `pig install --help` lists nothing to remove a package, so edit
`~/.pig/agent/settings.json` and drop the `extensions/plan` entry, or keep it
installed and simply never run `/plan`.

## Commands

| Command | Effect |
| --- | --- |
| `/plan` | toggle plan mode |
| `/plan on` / `/plan off` | toggle explicitly |
| `/plan go` | leave plan mode, restore tools, start executing the steps |
| `/plan show` (`/todos`) | print the list and progress |
| `/plan add <text>` | append a step |
| `/plan done <n\|text>` / `/plan undo <n\|text>` | tick / untick |
| `/plan rm <n>` | remove a step |
| `/plan clear` | drop the list |

`--plan` starts a run in plan mode.

## What plan mode does

- Removes `edit` / `write` / `patch` / `multi_edit` … from the active tool set.
- Blocks those tools again in `tool_call`, so a tool registered later cannot
  slip through.
- Filters `bash` through a read-only policy (`policy.go`): every command in the
  pipeline must start with a read-only verb, no redirection, no `git commit`,
  no `pkg install`, no `sed -i`, `find -delete`, … The `cd dir && rg …` shape is
  allowed.
- Injects a `plan_mode` system-prompt section (and removes it when the mode is
  off, so it never lingers).
- When the model answers with a `Plan:` section, extracts the numbered steps
  and shows a dialog: **执行计划 / 留在计划模式 / 细化计划**.
- During execution the model calls `plan_done` (step=n) after each step, or
  writes `[DONE:n]` in its text; both are applied as soon as they arrive
  (streamed text included), so the card ticks while the run continues. When
  every step is done the mode ends but the card stays, so you can see what
  happened.

## Where the state lives

Each change is appended to the session as a `plan-state` custom entry, so it
survives the app restarting pig (which it does when you change the context
window) and switching sessions. A different session starts with an empty plan.

The plan is **not** stored in the conversation, so it costs no tokens via
messages; it reaches the model through the prompt section only while plan mode
or execution is active.

## Display

The card is repainted on `session_start` (a new pig process) and on
`agent_start` (every turn), because `setWidget` is a push: the app cannot ask
for it again after a reconnect. The relay replays the last frame per widget key
when a client attaches, and the app keeps a per-session snapshot as a second
fallback.

The widget is one JSON line following
[docs/plan-widget.md](https://github.com/linuxwff789/pig-android/blob/main/docs/plan-widget.md):

```json
{"plan":{"steps":[{"n":1,"text":"Read agents.go","done":true}]}}
```

Plus a keyed status chip (`⏸ plan` or `📋 2/5`). The app renders it as a
checklist card; terminals that do not know the contract show the JSON text,
which is why the extension does not rely on `ui.custom`.
