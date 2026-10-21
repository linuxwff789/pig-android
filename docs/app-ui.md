# `app-ui/1` — declarative windows for the Pig app

Status: **M3.4 implemented** — all four placements, the component set plus forms
and tables, `builtin:` / `ext:` actions, `setWidget` panels, `setTitle`, debug
injection, and the file channel with hot reload / add-update-remove /
dismissal.

The goal is TUI-like extensibility without shipping executable UI code:
extensions (or a UI pack) write JSON, the app renders it with Compose.

## Where windows live

```
~/.pig/android-ui/
  manifest.json
  theme/colors.json
  layout/chat.json
  windows/deploy.json      ← one file per window
  windows/status.json
```

- The relay already pushes every text file in this tree (≤512 KiB each) as
  `relay_ui_pack`.
- The app treats every key matching `windows/*.json` as a window definition.
- Deleting the file closes the window. Editing it hot-updates the window.
- `id` is required and must be unique. Duplicate IDs: last file wins (sorted by
  path) and a warning is shown.
- A file that fails to parse keeps the previous good window with that `id` and
  sets `windowError`; other windows are unaffected.

The same schema can later be injected over a debug broadcast (no file needed)
for testing.

## Window object

```json
{
  "appUI": 1,
  "id": "deploy",
  "title": "Deploy",
  "placement": "overlay",
  "width": 340,
  "height": 420,
  "dismissible": true,
  "body": { "type": "column", "children": [] }
}
```

| Field | Default | Notes |
| --- | --- | --- |
| `appUI` | `1` | schema version; unknown higher versions are rejected |
| `id` | required | `[a-z0-9._-]{1,40}` |
| `title` | `id` | shown in the title bar |
| `placement` | `overlay` | `overlay`, `sheet`, `aboveComposer`, `belowComposer` |
| `width` / `height` | 320 / wrap | dp, clamped to the screen |
| `dismissible` | `true` | shows the close button and enables back-to-close |
| `body` | required | one node |

Windows are global, not tied to a session. Switching sessions keeps them open.

## Nodes

Every node supports `type`, `visible` (default true), `padding` (dp) and, inside
a `row`/`column`, `weight` (0 = natural size).

Containers:

| Type | Fields |
| --- | --- |
| `column` | `children`, `spacing`, `align`: `start/center/end`, `scroll`: bool |
| `row` | `children`, `spacing`, `align`: `start/center/end` |
| `divider` | — |
| `spacer` | `size` (dp) |

Content:

| Type | Fields |
| --- | --- |
| `text` | `text`, `style`: `body/heading/label/mono/muted/danger/markdown`, `align` |
| `code` | `text`, `language`, `maxLines` (default 20) |
| `progress` | `value` 0..1, or `indeterminate: true` |
| `button` | `label`, `action`, `style`: `primary/outline/text/danger`, `enabled` |

| `textfield` | `id`, `value`, `placeholder`, `maxLines` |
| `select` | `id`, `value`, `options`: `[{label,value}]` |
| `toggle` | `id`, `label`, `value` (bool) |
| `list` | `children` (dividers between them) |
| `table` | `headers`, `rows`: array of string arrays |

Form fields need an `id`; it becomes the JSON key sent with the action. A
missing `id` gets a generated `fieldN` and a warning.

M3.5 may add `webview` (off by default, local assets only).

Unknown types render a small `[unsupported: type]` placeholder and add a
warning; they never crash the window.

## Actions

```json
{ "type": "button", "label": "Retry", "action": "ext:deploy.retry" }
```

- `builtin:close` — close the window that owns the control (M3.1).
- `builtin:copy:<text>` — copy text to the clipboard (M3.1).
- `ext:<command>` — strip the prefix and send RPC `prompt` with `/<command>`.
  Dots, colons, underscores and dashes in command names were verified against a
  real extension. Form values are appended as one compact JSON argument:
  `/uitest.submit {"name":"alice","env":"dev","verbose":"true"}`.
- `prompt:<text>` — not implemented yet (would bypass the command registry).

`ext:` is deliberately deferred until a test extension command proves the
command-name rules (the `/skill:` namespace shows that colons are special).

## Limits and validation

- Tree depth ≤ 32, nodes ≤ 2000, text ≤ 16 KiB per field.
- Width/height clamped to 200..screen width/height.
- `padding`/`spacing` clamped to 0..48.
- Invalid values fall back to defaults with a warning; invalid JSON rejects the
  whole window.

## Lifecycle rules

1. **Add** — a new `windows/x.json` (or a debug injection) opens the window.
2. **Update** — same `id`, new content: update in place. Drag position, z-order
   and focus are preserved (UI state keyed by `id`, not by the tree).
3. **Remove** — file gone: close the window.
4. **Close** — the user closes it: remember `id` as dismissed; the file still
   exists, so later pack pushes must not reopen it. The dismissed set clears
   when the app process starts or when the file is removed.
5. **Invalid** — keep the last good window, set `windowError`, show it in the
   drawer.
6. **Session switch** — windows stay; their data may be stale until the
   extension rewrites the file.

## Extension widgets and title

RPC `extension_ui_request` now maps as follows:

- `setWidget` → a monospace panel above or below the composer, one per
  `widgetKey`; a request without `widgetLines` removes it.
- `setTitle` → the top-bar title (`Pig` is shown again when the title is empty).
- `setStatus` / `notify` already behaved as before.

These panels are independent of pack windows: they are session UI state set by
extensions, not files, and they survive UI pack pushes.

## Why not just `ctx.ui.custom()`?

RPC mode documents `custom()` as returning no component and `setWidget()` as
string lines only. Terminal components are not serializable. A JSON tree plus a
renderer is the portable equivalent, and it also works for non-terminal clients.

## Why not a WebView first?

A WebView panel can render anything (HTML/JS/canvas) and is the eventual escape
hatch, but it bypasses the pack's declarative guarantees, adds a JS bridge and
a security surface. Keep it as an optional node type after the native component
set proves useful.
