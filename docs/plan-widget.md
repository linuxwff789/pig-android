**English** · [简体中文](plan-widget.zh-CN.md)

# Plan widgets (`setWidget` contract, v1)

Any extension can show a plan as a native checklist card in the Android app —
there is no registry, no plugin id and no app-side allow-list. The app recognises
a widget by **key** and parses its **text lines**; everything that does not
follow this contract keeps the plain monospace widget it had before.

This is what lets a user pick a third-party plan extension: the app never needs
to know which one is installed.

## Widget keys

| Key (case-insensitive) | Meaning |
| --- | --- |
| `plan` | canonical |
| `plan-todos`, `plan_todos` | alias, for ports of Pi's plan-mode extension |
| `todos`, `todo` | alias |

`widgetPlacement` may be `aboveEditor` (default) or `belowEditor`; the card
follows it like any other widget.

## Two payload forms

Both use the normal `setWidget` request, so nothing outside the existing
`extension_ui_request` path is needed:

```json
{"type":"extension_ui_request","method":"setWidget","widgetKey":"plan",
 "widgetLines":["…"],"widgetPlacement":"aboveEditor"}
```

### A. JSON line (preferred)

The first non-blank line is one JSON object:

```json
{"plan":{"title":"Make the pin real","steps":[
  {"text":"Read agents.go","done":true},
  {"text":"Add relay_restart","done":true},
  {"text":"Write the docs","done":false}
]}}
```

- `plan` may be omitted (`{"title":…,"steps":[…]}` is accepted).
- `steps` items may be objects (`text`, optional `done`) or plain strings
  (treated as not done).
- `title` is optional; without it the app uses the pack string `plan.title`
  (`计划` in the shipped packs).
- Up to 200 steps are rendered; anything past that is ignored.

Extra lines after the JSON line are ignored, so an extension may append a note
without breaking the card.

### B. Text checklist (compatibility)

For extensions that already draw a terminal widget — including ports of Pi's
`examples/extensions/plan-mode` — the plain lines are enough:

```
Plan: Make the pin real
2/3
☑ Read agents.go
☑ Add relay_restart
☐ Write the docs
```

Recognised step syntax (one step per line):

| Form | Example |
| --- | --- |
| box symbols | `☐ text`, `☑ text`, `✅ text`, `✔ text`, `✓ text` |
| markdown boxes | `- [ ] text`, `* [x] text`, `[ ] text` |
| numbered list | `1. text`, `2) text` (counted as not done) |
| done by strikethrough | `~~text~~` |

Other rules:

- A title line is only recognised **before the first step**:
  `Plan: <text>`, `# Plan`, `📋 Plan: <text>`, or a bare `Plan`.
- A progress line (`2/5`, `📋 2/5`, `Plan 2/5`) is accepted and ignored — the
  app always derives progress from the steps.
- Blank lines, notes and anything else are ignored. If no step is recognised,
  the widget falls back to the plain text pane (it is never dropped).

## Rendering

- Header: title + `{done}/{total}` (pack string `plan.progress`).
- A thin progress bar, coloured `tertiary` when everything is done.
- Steps: `☑` + strike-through for done, `☐` for open; the **first open step** is
  highlighted in the primary colour.
- Read-only: the app cannot toggle a step back into an extension, because there
  is no reply channel for widgets. Extensions update their own state and emit a
  new widget line; a tap would only desync it.
- The list scrolls inside a 180 dp box, so a long plan cannot push the composer
  off screen.

## Collapse and dismiss

Both are app-side and local; the extension is not told about them (widgets have
no reply channel):

- **▾ / ▸** in the card header collapses the list to one line — title, progress
  and the current step. The state is remembered per widget key across launches.
- **✕** hides the card. It comes back the next time the extension sends
  *different* content, so hiding never swallows a real update. `/plan clear`
  (or the extension's own clear) removes it for good.

Dismissals are cleared when the session is switched, because widgets belong to
the agent of the session that wrote them.

## Status line is separate

Plan mode / progress chips are not part of this contract. Extensions may set any
`statusKey` (`plan`, `plan-mode`, …) and the status bar shows it unchanged next
to the app's own activity label. See [ui-pack.md](ui-pack.md) for the generic
status behaviour.

## Versioning

`v1` is additive: new keys, new optional JSON fields and new step syntaxes may be
added. Removing or re-meaning an existing form requires `v2` under a different
widget key.

## Testing from the shell (debug builds)

```bash
# JSON form
adb shell am broadcast -a com.linuxwff.pigapp.DEBUG_PLAN \
  -n com.linuxwff.pigapp/.DebugReceiver --es form json

# text form (Pi plan-mode style)
adb shell am broadcast -a com.linuxwff.pigapp.DEBUG_PLAN \
  -n com.linuxwff.pigapp/.DebugReceiver --es form text
```

Both go through the real `setWidget` event path and log the parsed result under
the `PigDebug` tag.

Any extension can emit the same thing. `widgetLines` is a JSON array of strings,
the first of which is the JSON payload:

```bash
adb shell am broadcast -a com.linuxwff.pigapp.DEBUG_EVENT \
  -n com.linuxwff.pigapp/.DebugReceiver \
  --es json '{"type":"extension_ui_request","method":"setWidget","widgetKey":"plan","widgetLines":["{\"plan\":{\"steps\":[{\"text\":\"one\",\"done\":true}]}}"]}'
```

## Which plan extension to use

The app is neutral on purpose — pick whichever you like, or keep several
installed and enable only one:

- `extensions/plan/` in this repository — optional Go extension, emits form A
  and implements plan mode (read-only tools, `[DONE:n]`, execution prompt).
- Pi's `packages/coding-agent/examples/extensions/plan-mode` — the TypeScript
  original; its `☐/☑` widget is accepted as-is by form B.
- Your own — anything that sets a plan widget key with one of the two payload
  forms works.

Nothing needs to be registered with the app, and an extension that does not
follow the contract is not affected: it just keeps the generic text widget.
