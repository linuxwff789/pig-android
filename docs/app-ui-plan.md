**English** · [简体中文](app-ui-plan.zh-CN.md)

# M3 implementation plan — declarative windows

Goal: a TUI-like surface where any extension can open, update and close windows
by writing JSON, without shipping code to the phone.

Contract: [`docs/app-ui.md`](app-ui.md). This file is the execution order, the
acceptance criteria and the pitfall checklist.

## Ground rules

- One milestone at a time: implement → CI green → install → verify from the
  shell → commit. No milestone starts before the previous one is verified on the
  device.
- Every new capability gets a **debug hook** (`DEBUG_WINDOW`, `DEBUG_ACTION`,
  `DEBUG_STATE`) so verification never depends on touch, logcat or screenshots.
- JSON parsing is total: bad input degrades to a warning + last good state,
  never a crash. This is the property most likely to break in the field.
- Do not change the existing `relay_ui_pack` wire format. Windows ride in the
  same `files` map under `windows/`.

## M3.1 — renderer + overlay + debug injection — DONE

Verified on device: injected a 340×460 window (`text`, `muted`, `progress`,
`code`, `button`), state showed
`windows=[AppWindow(id=demo, title=Deploy, 340x460)]`; `DEBUG_CLOSE_WINDOW`
emptied it; invalid JSON set `windowError` without affecting the app. Visual
confirmation is pending because `uiautomator dump` starts returning
`null root node` on this device; state-file assertions are the fallback.

Scope:

- `AppWindow` / `UiNode` model and parser (`app-ui/1`), depth/node limits.
- Components: `column`, `row`, `divider`, `spacer`, `text`, `code`, `progress`,
  `button`.
- One `overlay` placement: floating card, title bar, drag, close, focus/z-order.
- `builtin:close` and `builtin:copy:<text>`.
- Debug: `DEBUG_WINDOW --es json '<window>'`, `DEBUG_CLOSE_WINDOW --es id`,
  state exposes `windows=[{id,title,placement}]` and `windowError`.

Acceptance:

- Inject a window with all eight components; it renders above the chat.
- Drag only moves within screen bounds; rotation keeps it visible.
- Close hides it; a second `DEBUG_WINDOW` with the same id reopens/updates it.
- Invalid JSON → `windowError`, no window, app still usable.
- Back closes the top window before leaving the app.

## M3.2 — file channel + hot reload — DONE

Verified on device with `windows/deploy.json`: file appeared in the state as
`AppWindow(id=deploy, title=Deploy v1, 340x440)`; editing it became
`Deploy v2, 340x500` without restart; closing it and editing again stayed
closed; deleting the file cleared the dismissal and re-adding it opened
`Deploy v4`; broken JSON kept the last good window and set
`windowError=windows/deploy.json: JSON 解析失败…`.

Scope:

- Derive windows from `relay_ui_pack` keys matching `windows/*.json`.
- Reconcile on every pack: add, update (preserve position/z), remove.
- Dismissed set: a user-closed window does not reopen on the next pack push;
  clears on process start or when the file is removed.
- Per-window parse errors keep the last good window and set `windowError`.

Acceptance:

- `echo ... > ~/.pig/android-ui/windows/demo.json` → window appears <2 s.
- Editing only the body updates in place; drag position survives.
- Deleting the file closes the window.
- Writing broken JSON keeps the old window and surfaces the error.
- Theme/layout edits still apply while a window is open.

## M3.3 — actions + forms — DONE

Verified the host's command-name rules with a throwaway extension: `nt.dot`,
`nt:colon`, `nt_underscore`, `nt-dash` all dispatched and received their args.
End-to-end: a pack window with `textfield`/`select`/`toggle`/`table`, values set
via debug, the Submit button (`ext:uitest.submit`) produced
`{"name":"alice","env":"dev","verbose":"true"}` in the extension's log. The test
extension and its settings entry were removed afterwards.

Scope:

- `ext:` actions → RPC `prompt` with `/<command>`; verify command-name rules
  with a throwaway Go extension first.
- `textfield`, `select`, `toggle` with local state; on action, values are sent
  as a JSON argument.
- `list` (static items) and `table` (fixed columns).
- `prompt:` actions (opt-in via manifest `allowPromptActions: true`).

Acceptance:

- A test extension exposes `/uitest.submit`; the button reaches it and receives
  the form JSON.
- Invalid/disabled actions show no crash and log a warning.
- Windows can be closed while a form is being edited.

## M3.4 — placements + TUI parity — DONE

Verified: pack windows with `placement: aboveComposer`, `belowComposer` and
`sheet` appeared in the state with the right placement; the sheet closed via
`DEBUG_CLOSE_WINDOW`; `setWidget` set/cleared a panel
(`widgets=[AppWidget(key=demo, lines=…, placement=aboveEditor)]` → `[]`);
`setTitle` set/cleared `titleOverride`. Sending a normal prompt still returned
`OK` after moving the composer insets into the bottom column.
`prompt:` actions remain unimplemented on purpose.

Scope:

- `sheet`, `aboveComposer`, `belowComposer`, `sidebar` placements.
- Render `extension_ui_request.setWidget` string lines as a TUI-style panel
  (above/below composer) so existing extensions get visible output.
- `setTitle` → optional top-bar title override.

Acceptance:

- Existing `setWidget` callers show their lines without changes.
- Widgets and windows coexist; composer inset/IME still correct.

## M3.5 — optional escape hatch

Scope:

- `webview` node: local asset only, no network, narrow JS bridge (`action`,
  `value`).
- Disabled unless the pack opts in (`manifest.webview: true`).

Acceptance:

- A chart demo renders; no network access; closing the window releases the
  WebView; JS cannot invoke arbitrary app methods.

## Pitfall checklist

| Risk | Mitigation |
| --- | --- |
| Overlay under the keyboard | `imePadding` on the overlay layer; test with a textfield in a window |
| Back button exits the app with a window open | `BackHandler` closes the top window first |
| Drag/resize off-screen, rotation | Clamp to window bounds on every drag and on configuration change |
| Pack push resets drag/z-order | Key UI state by window `id`; never rebuild state from the tree |
| Closed window reopens on next push | Dismissed set, cleared on process start / file removal |
| Duplicate `id` across files | Deterministic last-file-wins + warning |
| `ext:` collides with `/skill:` parsing | Verify a colon-free mapping first (`ext:` stripped, `/uitest.submit`) |
| Relay 512 KiB/file / binary skip | Windows are small JSON; document the limit; validate size app-side |
| Huge/deep trees freeze the UI | Depth 32, 2000 nodes, clamp text, render unsupported as placeholder |
| Invalid JSON kills all windows | Parse per window; keep last good by `id` |
| Windows outlive their session | Documented: windows are global; extensions rewrite on session change |
| Process death | Rebuild from the pack on launch; dismissed set is intentionally not persisted |
| Theme edit recolors an open window | Expected; colors come from `PigTheme` |
| Session switch clears the transcript but not windows | Intentional; windows are UI, not session data |
| Unsupported node types | Placeholder + warning, never an exception |
| Accessibility | Title/buttons expose content descriptions; min touch target 48dp |

## Verification loop (every milestone)

```bash
# build + install
gh run watch <id> --exit-status
pm install -r /data/local/tmp/pig.apk

# inject and inspect without touch
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_WINDOW --es json '…'
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_STATE
cat /storage/emulated/0/Android/data/com.linuxwff.pigapp/files/state.txt   # via root

# file channel
printf '%s' '…' > ~/.pig/android-ui/windows/demo.json
uiautomator dump …   # text/buttons only; colors need the user's eyes

# regressions
DEBUG_PROMPT / DEBUG_SWITCH / UI pack edit / rotation
```

State must expose enough to assert without screenshots: `windows`, focused id,
`windowError`, last pack hash.

## Order of work

1. M3.1 model + parser + overlay + debug injection.
2. M3.1 verification + commit.
3. M3.2 file channel + dismiss/reconcile + verification.
4. M3.3 actions + forms (including the test extension).
5. M3.4 placements + `setWidget` parity.
6. M3.5 only if the native set proves insufficient.

Anything not needed for the milestone goes to the scratchpad, not into the same
build.
