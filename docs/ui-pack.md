# UI packs (M1)

A UI pack is a directory of JSON files. `pig-relay` watches
`~/.pig/android-ui/` (800 ms poll), hashes the tree and pushes the whole pack to
the app. The app applies it immediately — no rebuild, no restart.

```
~/.pig/android-ui/
  manifest.json
  theme/colors.json
  theme/typography.json
  layout/chat.json
  strings.json
  components.json
  icons.json
  nav.json
  windows/
  assets/
```

`strings.json`, `components.json` and `icons.json` are optional and are
discovered by filename (no manifest entry needed).

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

| Key | Type | Default | Used for |
| --- | --- | --- | --- |
| `background` | color | `#0A0C12` | app background |
| `surface` | color | `#12151E` | composer, cards, drawer sheet |
| `surfaceVariant` | color | `#1A1F2B` | tool cards, chips, read-only banner |
| `primary` | color | `#A78BFA` | labels, buttons, current session |
| `secondary` | color | `#7DD3FC` | tool icons, steering/pending status |
| `accent` | color | `#22D3EE` | end of the brand gradient (avatar, send button) |
| `text` | color | `#ECEEF5` | body text |
| `textMuted` | color | `#98A2B8` | hints, metadata |
| `danger` | color | `#FB7185` | errors, stop button |
| `cornerRadius` | int 0–40 | `18` | cards, bubbles, sheets |
| `avatarShadow` | bool | `false` | default glow behind per-message avatars |
| `bubbleShadow` | bool | `true` | default shadow under user message bubbles |
| `onPrimary` | color? | derived | text/icon color on primary surfaces |
| `onSecondary` | color? | derived | text/icon color on secondary surfaces |
| `onAccent` | color? | derived | text/icon color on accent surfaces |
| `outline` | color? | textMuted 40% | borders and dividers |
| `outlineVariant` | color? | textMuted 18% | subtle dividers |
| `success` | color | `#4ADE80` | diff added lines / success |
| `warning` | color | `#FBBF24` | warnings |
| `scrim` | color | `#9E000000` | dialog scrim |
| `bubbleStart` | color? | primary | user bubble gradient start |
| `bubbleEnd` | color? | accent | user bubble gradient end |
| `cardRadius` | int? | `cornerRadius` | tool/card radius override |
| `bubbleRadius` | int? | `cornerRadius+4` | user bubble radius override |
| `chipRadius` | int? | `cornerRadius/3` | chips and small buttons |
| `composerRadius` | int? | `cornerRadius+8` | composer surface radius |
| `composerElevation` | int 0–40 | `12` | composer shadow |
| `bubbleElevation` | int 0–40 | `8` | user bubble shadow |
| `fabElevation` | int 0–40 | `6` | jump-to-bottom pill |
| `bubbleStartAlpha` | float 0–1 | `0.30` | bubble gradient start alpha |
| `bubbleEndAlpha` | float 0–1 | `0.16` | bubble gradient end alpha |
| `bubbleBorderAlpha` | float 0–1 | `0.32` | bubble border alpha |
| `cardBorderAlpha` | float 0–1 | `0.60` | tool card border alpha |
| `chipAlpha` | float 0–1 | `0.15` | selected chip background alpha |

Colors accept `#RGB`, `#RRGGBB` and `#AARRGGBB` (the parser also tolerates a
missing `#`). An invalid value is reported as a warning and that one field falls
back to its default; the rest of the pack still applies.

### Theme hot reload and precedence

The whole pack is replaced on every hash change. There is no partial merge
between packs: a key omitted in the new file goes back to the built-in default.

`avatarShadow` and `bubbleShadow` are **defaults**, not hard overrides:

- if the user has never touched the matching switch in **设置**, the pack value is
  used and changing `theme/colors.json` hot-reloads it;
- once the user toggles 消息头像阴影 / 气泡阴影, the choice is stored in the app's
  `pig` SharedPreferences (`avatarShadow`, `bubbleShadow`) and wins over the pack;
- the user can therefore ship a theme with shadows on/off, while an individual
  user can still opt out without editing the pack.

The same precedence applies after an app restart: the pack supplies the default,
the stored preference (if present) overrides it.

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

## assets/

Binary files under `assets/` are sent as base64 in the pack (512 KiB per file)
for `image` nodes:

```json
{"type":"image","src":"assets/logo.png","width":120,"height":120,"alt":"Pig logo"}
```

## nav.json

Optional drawer entries. The relay pushes it with the rest of the pack.

```json
{
  "items": [
    {
      "id": "providers",
      "label": "模型供应商",
      "window": "providers",
      "command": "provider-ui",
      "icon": "build",
      "placement": "settings"
    }
  ]
}
```

- `window` is the id of a cached `windows/<id>.json`; tapping the entry restores
  it even if the user closed it earlier.
- `command` is a slash command (leading `/` optional); it runs after the window
  is restored so the extension can refresh it.
- `icon` accepts `build`, `refresh` or `add` (unknown values fall back to build).
- `placement` is `drawer` (default, listed under 面板) or `settings` (rendered in
  the app's settings dialog, e.g. 模型供应商). This is data-only: moving an entry
  between the drawer and settings needs no app rebuild.
- A window referenced by `nav.json` is
  **on demand**: it is not auto-opened when the pack loads (including app
  restarts); tapping the entry restores it. A window without a nav entry still
  opens automatically when its file appears.

## strings.json

All user-facing labels, placeholders, status text and accessibility
descriptions. Keys are flat or nested; nested keys are flattened with dots
(`{"header":{"online":"在线"}}` → `header.online`). Missing keys fall back to the
built-in Chinese defaults, so a pack can override only a few strings.

```json
{
  "header": {
    "title": "Pig", "online": "在线", "offline": "离线",
    "usage": "  ·  {percent}% · {used}/{window}",
    "compacted": "  ·  已压缩", "cache": "  ·  缓存 {rate}%"
  },
  "drawer": { "newSession": "新会话", "settings": "设置", "recent": "最近会话" },
  "status": { "thinking": "思考中", "working": "工作中", "compacting": "压缩上下文" },
  "tool": { "running": "运行中…", "failed": "失败", "collapse": "收起", "summary": "摘要", "expand": "展开" },
  "composer": { "steerPlaceholder": "插队指令…", "steerHint": "点发送=插队 · 长按=排队" },
  "empty": { "title": "开始聊天", "hint": "在下面输入，或试试：", "suggestions": "问题一|问题二|问题三" }
}
```

Placeholders use `{name}` and are replaced by the app:

| Placeholder | Used by |
| --- | --- |
| `{percent}`, `{used}`, `{window}` | header usage |
| `{rate}` | header prompt-cache hit rate |
| `{count}` | pending banner, tool groups, line counts |
| `{tool}` | tool group header |
| `{name}` | session delete dialog |
| `{id}` | drawer session pill |

`empty.suggestions` is a `|`-separated list. Status text is keyed:
`thinking`, `working`, `compacting`, `retrying`, `switching`, `restoring`.

`header.cache` is the cumulative session cache hit rate
(`cacheRead / (input + cacheRead + cacheWrite)`). Right after `/compact` PiG
reports a null `contextUsage`; the header shows `header.compacted` until the
next model call provides a fresh usage value.

## components.json

Boolean/size switches for the built-in components. All keys are optional and
clamped; the values below are the defaults.

| Key | Type | Default | Effect |
| --- | --- | --- | --- |
| `showHeaderAvatar` | bool | true | header brand avatar |
| `headerAvatarSize` | int 16–96 | 36 | header avatar dp |
| `showHeaderOnline` | bool | true | online dot + label |
| `showDrawerBrand` | bool | true | drawer header card |
| `showPanels` | bool | true | nav panel section |
| `showSessions` | bool | true | recent sessions section |
| `showSessionBadges` | bool | true | 运行中/已打开/开过/历史 badges |
| `showSessionSize` | bool | true | `· 123K` in session rows |
| `showCopy` | bool | true | per-message copy button |
| `showSelection` | bool | true | long-press text selection |
| `showSuggestions` | bool | true | empty-state suggestion chips |
| `showReadOnlyBanner` | bool | true | history-session banner |
| `showPendingBanner` | bool | true | steering/pending banner |
| `showSteerHint` | bool | true | “点发送=插队” hint |
| `showStatusBar` | bool | true | transient status bar |
| `assistantAvatar` | bool | true | avatar next to assistant messages |
| `messageAvatarSize` | int 12–64 | 24 | message avatar dp |
| `bubbleMaxWidthDp` | int 200–1200 | 520 | user bubble max width |
| `toolSummaryLines` | int 1–20 | 4 | summary-mode line limit |
| `toolExpandedLines` | int 20–2000 | 200 | expanded tool output limit |
| `codeFenceMaxLines` | int 20–2000 | 400 | markdown fenced-code limit |
| `tableMaxLines` | int 1–20 | 6 | markdown table cell limit |
| `composerButtonSize` | int 32–72 | 46 | send/stop button dp |

## icons.json

Maps a component key to a built-in icon name. Unknown names fall back to the
component default; unknown keys are ignored.

```json
{
  "menu": "menu", "send": "send", "stop": "stop", "copy": "copy",
  "settings": "settings", "refresh": "refresh", "add": "add",
  "tool": "build", "more": "more", "edit": "edit", "delete": "delete",
  "check": "check", "search": "search", "history": "history",
  "chevronDown": "down", "chevronUp": "up"
}
```

Built-in names: `menu`, `send`, `stop`, `refresh`, `add`, `settings`, `build`
(alias `tool`), `check`, `delete`, `edit`, `more`, `search`, `copy`, `history`,
`down`, `up`, `close`, `warning`.

## layout/chat.json

```json
{
  "sidebar": { "widthDp": 300 },
  "chat": {
    "assistantLabel": "PIG",
    "toolCards": "collapsed",
    "toolGroups": true,
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
- `chat.toolGroups`: when `true` (default), consecutive tool cards with the same
  tool name are folded into one group header (`bash × 5`). The group is collapsed
  by default; expanding it shows the individual cards, which are themselves
  collapsed according to `toolCards` — this gives two collapse layers and keeps
  long tool runs cheap to render. Set it to `false` to render every tool card
  directly.
- `chat.toolCards`: initial tool-card mode:
  - `collapsed` (default) — a single row with the tool name; tap to open 摘要;
  - `collapsible` — start in 摘要 (headline, metrics, first lines);
  - `expanded` — full output.
  The expanded card has 收起 / 摘要 / 展开 chips; long-press a chip to apply that
  mode to every tool card. `write` reports lines and bytes, `edit` reports
  added/removed lines and renders the diff with colored +/- lines.
- `chat.showUsage` adds the context usage (percentage plus used/total tokens) to the
  top bar; the app refreshes it on connect, session switch, model change and
  after every turn.
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
