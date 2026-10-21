# UI packs (M1)

A UI pack is a directory of JSON files. `pig-relay` watches
`~/.pig/android-ui/` (800 ms poll), hashes the tree and pushes the whole pack to
the app. The app applies it immediately — no rebuild, no restart.

```
~/.pig/android-ui/
  manifest.json
  theme/colors.json
  layout/chat.json
```

Start from `examples/ui-pack/`:

```bash
mkdir -p ~/.pig/android-ui
cp -r examples/ui-pack/* ~/.pig/android-ui/
```

Edit any file and the app restyles within a second.

## manifest.json

```json
{
  "name": "Pig default",
  "version": 1,
  "theme": "theme/colors.json",
  "layout": "layout/chat.json"
}
```

`theme` and `layout` are paths relative to the pack root. Both are optional; a
missing file falls back to the built-in defaults and reports a warning.

## theme/colors.json

| Key | Default | Used for |
| --- | --- | --- |
| `background` | `#0E1014` | app background |
| `surface` | `#161A22` | composer, drawer |
| `surfaceVariant` | `#1E2430` | tool cards |
| `primary` | `#8B7CFF` | labels, buttons, user bubble |
| `secondary` | `#4CD7F6` | tool icons, connected state |
| `text` | `#E6E8EF` | body text |
| `textMuted` | `#A8B0C0` | hints, status |
| `danger` | `#FF6B6B` | errors |
| `cornerRadius` | `16` | reserved (0–40) |

Colors accept `#RRGGBB` and `#AARRGGBB`. An invalid color is reported and that
one field falls back to its default; the rest of the pack still applies.

## theme/typography.json

Optional; `manifest.json` points at it with `"typography": "theme/typography.json"`.
Without the file the defaults below apply.

| Key | Default | Used for |
| --- | --- | --- |
| `body` | 15 | assistant/user body text, markdown paragraphs |
| `label` | 11 | status, session metadata, table headers |
| `h1`…`h6` | 21/19/17/15/14/13 | markdown headings and app titles |
| `code` | 12 | fenced code blocks and inline code |
| `lineHeight` | 1.5 | multiplier for body text |

Sizes are in sp and clamped to 8–40; `lineHeight` is clamped to 1.0–2.5.
These values feed `PigTheme`'s `Typography` and the markdown renderer, so they
hot-reload with the rest of the pack.

## layout/chat.json

```json
{
  "sidebar": { "widthDp": 300 },
  "chat": {
    "assistantLabel": "PIG",
    "toolCards": "collapsible",
    "showUsage": true
  },
  "composer": {
    "placeholder": "给 Pig 发消息…",
    "maxLines": 6,
    "sendOnEnter": true
  }
}
```

- `sidebar.widthDp` is clamped to 240–560.
- `chat.toolCards`: initial tool-card mode — `collapsible`/`collapsed` start in
  summary, `expanded` shows the full output. The card always has 收起 / 摘要 /
  展开 chips; `write` reports lines and bytes, `edit` reports added/removed
  lines and renders the diff with colored +/- lines.
- `chat.showUsage` adds the context percentage to the top bar.
- `composer.maxLines` is clamped to 1–12.
- `sendOnEnter` changes the IME action to Send.

Unknown keys are ignored. Invalid JSON in a file produces a warning, keeps the
previous good value for that file's fields and still applies the rest of the
pack. A broken `manifest.json` is the only case that rejects the whole pack.

## Wire format

The relay sends one frame per change:

```json
{"type":"relay_ui_pack","hash":"<sha256>","files":{"manifest.json":"…","theme/colors.json":"…"}}
```

`hash` covers sorted `path\0content\0` pairs. The app keeps the parsed pack in
`AppUiState.uiPack`, so `DEBUG_STATE` shows the effective values (colors are
printed as Compose `Color(r,g,b,a)`).
