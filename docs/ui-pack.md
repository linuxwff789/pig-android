# UI packs (M1)

A UI pack is a directory of JSON files. `pig-relay` watches
`~/.pig/android-ui/` (800 ms poll), hashes the tree and pushes the whole pack to
the app. The app applies it immediately — no rebuild, no restart.

```
~/.pig/android-ui/
  manifest.json
  theme/colors.json
  theme/typography.json
  skins.json
  layout/chat.json
  strings.json
  components.json
  icons.json
  nav.json
  windows/
  assets/
```

`strings.json`, `components.json`, `icons.json` and `skins.json` are optional
and are discovered by filename (no manifest entry needed).

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
| `fontFamily` | `sans` | body/UI font: `sans`, `serif`, `mono`, `cursive`, or `assets/fonts/x.ttf` |
| `headingFontFamily` | empty | headings; empty falls back to `fontFamily` |
| `labelFontFamily` | `mono` | labels, metadata, section headers |
| `codeFontFamily` | `mono` | code blocks, inline code, monospace nodes |
| `bodyWeight` | 400 | body font weight (100–900 or `normal`/`medium`/`bold`) |
| `headingWeight` | 600 | heading font weight |
| `labelWeight` | 500 | label font weight |
| `letterSpacing` | 0 | extra letter spacing in sp, clamped to -2–4 |

Sizes are in sp and clamped to 8–40; `lineHeight` is clamped to 1.0–2.5.
These values feed `PigTheme`'s `Typography` and the markdown renderer, so they
hot-reload with the rest of the pack.

### Custom font files

Put fonts under `assets/fonts/` and reference the asset path:

```json
{
  "fontFamily": "assets/fonts/Inter-Regular.ttf",
  "headingFontFamily": "assets/fonts/NotoSerif-Regular.ttf",
  "labelFontFamily": "assets/fonts/CutiveMono.ttf",
  "codeFontFamily": "assets/fonts/CutiveMono.ttf",
  "bodyWeight": 400,
  "headingWeight": 700,
  "letterSpacing": 0.2
}
```

- Supported: `.ttf`, `.otf`, `.ttc`; each file ≤512 KiB (the relay asset limit).
- The app decodes the bytes from `relay_ui_pack.assets` into its cache and loads
  them with Compose `Font(file)`; changing a font file changes the pack hash and
  hot-reloads it.
- A missing/invalid file falls back to the built-in family name.
- Built-in names: `sans`/`sans-serif`, `serif`, `mono`/`monospace`/`code`,
  `cursive`.

## assets/

Binary files under `assets/` are sent as base64 in the pack (512 KiB per file)
for `image` nodes and `fonts/` files:

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
  "tool": {
    "running": "运行中…", "failed": "失败", "collapse": "收起",
    "summary": "摘要", "expand": "展开",
    "metrics": {
      "lines": "{count} 行",
      "linesBytes": "{count} 行 · {bytes}",
      "diff": "+{added} −{removed}",
      "exit": "exit {code} · {count} 行"
    }
  },
  "composer": { "steerPlaceholder": "插队指令…", "steerHint": "点发送=插队 · 长按=排队" },
  "empty": { "title": "开始聊天", "hint": "在下面输入，或试试：", "suggestions": "问题一|问题二|问题三" },
  "commands": { "compact": "压缩上下文", "new": "新建会话", "name": "重命名会话：/name 名称" },
  "skin": { "title": "皮肤" },
  "setup": { "title": "Termux 后端还没准备好", "oneClick": "一键安装", "retry": "重试" },
  "dialog": { "cancel": "取消", "allow": "允许", "ok": "确定", "deny": "拒绝" }
}
```

Placeholders use `{name}` and are replaced by the app:

| Placeholder | Used by |
| --- | --- |
| `{percent}`, `{used}`, `{window}` | header usage |
| `{rate}` | header prompt-cache hit rate |
| `{count}` | pending banner, tool groups, line counts |
| `{tool}` | tool group header |
| `{name}` | session delete/switch dialog, pack name |
| `{id}` | drawer session pill, settings session info, no-window toast |
| `{file}` | settings session info |
| `{title}` | window close accessibility label |
| `{bytes}` | tool write metrics |
| `{code}` | bash exit-code metric, relay exit |
| `{added}`, `{removed}` | edit diff metrics |
| `{label}` | drawer panel toasts |
| `{action}` | unknown `ext:` action toast |
| `{command}` | slash-command toasts |
| `{mode}` | tool-mode toast |
| `{hash}`, `{cwd}`, `{files}`, `{version}` | settings about |
| `{error}` | relay/pack error messages |

`empty.suggestions` is a `|`-separated list. Status text is keyed:
`thinking`, `working`, `compacting`, `retrying`, `switching`, `restoring`.

`header.cache` is the cumulative session cache hit rate
(`cacheRead / (input + cacheRead + cacheWrite)`). Right after `/compact` PiG
reports a null `contextUsage`; the header shows `header.compacted` until the
next model call provides a fresh usage value.

### Remaining key groups

| Group | Used by |
| --- | --- |
| `header.*` | title, online/offline, model placeholder, usage/cache/compacted |
| `drawer.*`, `session.*` | sidebar, session badges, rename/delete/switch dialogs |
| `status.*`, `banner.*`, `composer.*` | status bar, banners, input area |
| `tool.*` | tool cards, groups, metrics templates, failed/running labels |
| `markdown.*` | code fence language/lines and 收起/展开 |
| `model.*` | model picker |
| `settings.*` | settings sheet and about/session info |
| `commands.*` | built-in slash-command descriptions in the palette |
| `setup.*` | first-run Termux setup dialog |
| `dialog.*` | extension confirm/input/select dialogs |
| `relay.*`, `error.*` | launcher errors, connection errors, pack errors |
| `notification.*` | foreground-service notification (best-effort: the service may start before a pack is loaded, then falls back) |
| `a11y.*` | menu/stop/reconnect/copy/close/window-close labels |
| `brand.emoji` | avatar glyph (default 🐷) |
| `skin.title`, `toast.skinChanged` | skin switcher heading / confirmation |
| `pack.*` | pack manager dialog, built-in/local rows, import buttons |
| `toast.packImported`, `toast.packImportFailed`, `toast.packMissing` | pack manager feedback |
| `pack.actionTitle`, `pack.actionConfirm`, `pack.actionMissing`, `pack.actionDenied`, `pack.actionNeedsModel`, `pack.actionNeedsLevel` | pack RPC actions |
| `toast.*` | short confirmations and validation errors |

Every literal in these groups has a built-in Chinese fallback, so a pack can
ship only the keys it wants to change. The command palette combines
`commands.*` with the extension commands returned by RPC `get_commands`; newly
installed extensions appear after `/reload-runtime` (or an agent restart) and
the next palette refresh.

## header

`header` is optional. Omit it to keep the built-in header. When present, it
replaces the whole top bar and every element is explicit.

```json
"header": {
  "show": true,
  "height": 74,
  "paddingH": 12,
  "paddingV": 3,
  "background": "gradient",
  "gradientAlpha": 0.24,
  "divider": true,
  "rows": [
    {
      "spacing": 8,
      "align": "start",
      "elements": [
        { "type": "menu", "size": 22 },
        { "type": "brand", "avatarSize": 36, "online": true },
        { "type": "spacer" },
        { "type": "usage", "bind": "stats.contextUsage", "format": "{percent}%" },
        { "type": "cache", "bind": "stats", "format": "缓存 {cacheHitRate}%" },
        { "type": "stop", "when": "busy" },
        { "type": "reconnect", "when": "offline" }
      ]
    },
    {
      "spacing": 8,
      "align": "start",
      "height": 28,
      "elements": [
        { "type": "model" },
        { "type": "spacer" },
        { "type": "icon", "icon": "settings", "action": "settings", "size": 18 }
      ]
    }
  ]
}
```

Container keys:

| Key | Default | Notes |
| --- | --- | --- |
| `show` | true | `false` removes the header entirely |
| `height` | 0 | minimum height in dp (0 = wrap rows) |
| `paddingH` / `paddingV` | 12 / 2 | inner padding, clamped |
| `background` | `gradient` | `gradient`, `surface`, `transparent` |
| `gradientAlpha` | 0.13 | only for `gradient` |
| `divider` | false | bottom divider |
| `body` | null | **pixel-level** appUI node tree; when present `rows` is ignored |
| `rows` | [] | simple row/element list (see below) |

### Pixel-level header (`header.body`)

For full composition, put an `appUI` node tree in `header.body`. It uses the
same nodes as `windows/*.json` (`column`, `row`, `text`, `code`, `progress`,
`button`, `textfield`, `select`, `toggle`, `list`, `table`, `image`, `divider`,
`spacer`, `icon`, `avatar`, `connection`), plus `bind` / `format` and
`visibleWhen`.

```json
"header": {
  "height": 74,
  "background": "gradient",
  "divider": true,
  "body": {
    "type": "column",
    "spacing": 2,
    "children": [
      {
        "type": "row",
        "spacing": 8,
        "children": [
          { "type": "icon", "icon": "menu", "size": 22, "action": "header:menu" },
          { "type": "avatar", "size": 36, "action": "header:menu" },
          {
            "type": "column", "weight": 1, "spacing": 0,
            "children": [
              { "type": "text", "text": "Aurora", "style": "heading" },
              { "type": "connection", "color": "tertiary" }
            ]
          },
          { "type": "text", "bind": "stats.contextUsage", "format": "{percent}%", "style": "muted" },
          { "type": "text", "bind": "stats", "format": "缓存 {cacheHitRate}%", "style": "muted" },
          { "type": "icon", "icon": "stop", "size": 22, "action": "header:stop", "visibleWhen": "busy" }
        ]
      },
      {
        "type": "row", "spacing": 8,
        "children": [
          { "type": "text", "bind": "state.model", "format": "{provider}/{id}", "style": "muted" },
          { "type": "spacer", "weight": 1 },
          { "type": "icon", "icon": "settings", "size": 18, "action": "header:settings" },
          { "type": "icon", "icon": "add", "size": 18, "action": "header:new_session" }
        ]
      }
    ]
  }
}
```

Header actions in node trees:

| `action` | Effect |
| --- | --- |
| `header:menu` | open drawer |
| `header:model` | open model picker |
| `header:reconnect` | reconnect |
| `header:stop` / `header:abort` | abort current turn |
| `header:settings` | open settings |
| `header:new` / `header:new_session` | new session |
| `pack:<name>` | run a `layout.actions` entry |

`builtin:copy:*` and `ext:*` also work through the normal window action path.

### Simple rows (`header.rows`)

Elements:

| `type` | What it renders |
| --- | --- |
| `menu` | drawer menu icon |
| `brand` | avatar + title + optional online dot |
| `title` | title text only |
| `model` | model label + chevron; tap opens the picker |
| `usage` | context usage; default app string or `bind`/`format` |
| `cache` | cache hit rate; default app string or `bind`/`format` |
| `connection` | dot + 在线/离线 |
| `status` | current status text (`thinking`, `working`, …) |
| `stop` | stop icon |
| `reconnect` | reconnect icon |
| `settings` | settings icon |
| `newSession` | new session icon |
| `icon` | arbitrary built-in icon from `icons.json` |
| `text` | text, with `bind`/`format` |
| `pill` | translucent `InfoPill` |
| `divider` | vertical divider |
| `spacer` | flexible space; `weight` (default 1) |

Element keys:

| Key | Notes |
| --- | --- |
| `visible` | default true |
| `when` | `always` (default), `busy`, `idle`, `connected`, `offline`, `streaming`, `compacting` |
| `size` | icon/divider size in dp |
| `weight` | row weight (0 = natural size) |
| `padding` | outer padding |
| `icon` | icon name override |
| `label` / `text` | static fallback text |
| `style` | `label` (default), `body`, `heading`, `mono`, `muted`, `danger` |
| `color` | `onSurfaceVariant` (default), `primary`, `secondary`, `tertiary`, `onSurface`, `error`, `success`, `warning` |
| `bind` + `format` | read RPC data |
| `action` | `menu`, `model`, `reconnect`, `stop`/`abort`, `settings`, `new`/`new_session`, `header:*`, `pack:<name>` |
| `avatar` / `avatarSize` / `online` | brand element only |

The header is rendered inside `statusBarsPadding()`, so `paddingV` is added
below the system status bar.

## drawer / chat appearance

These keys only change visuals: sizes, spacing, colors, radii and decorative
visibility. They do not reorder menu actions or change messaging behavior.

`layout/chat.json` → `drawer`:

| Key | Default | Effect |
| --- | --- | --- |
| `background` | `theme` | `theme`, `surface`, `gradient`, `transparent` |
| `gradientAlpha` | 0.18 | drawer gradient strength |
| `cornerRadius` | theme | drawer sheet radius; `-1` = theme |
| `paddingH` / `paddingV` | 16 / 20 | drawer content padding |
| `spacing` | 10 | space between drawer sections |
| `brandAvatarSize` | 44 | brand avatar dp |
| `brandTagline` | true | show the tagline |
| `brandCardAlpha` | 0.34 | brand card gradient/opacity |
| `brandPills` | true | show online/model/session pills |
| `buttonHeight` | 0 | min height for drawer buttons; `0` = natural |
| `buttonRadius` | -1 | drawer button radius; `-1` = theme |
| `buttonIconSize` | 16 | drawer button icon dp |
| `sectionLabelSize` | 0 | section heading size; `0` = theme label size |
| `sectionLabelUppercase` | false | uppercase section headings |
| `sessionRowRadius` | -1 | session row radius; `-1` = theme |
| `sessionSelectedAlpha` | 0.13 | selected session background alpha |
| `showSessionId` | true | show the 8-char session id |
| `showSessionTime` | true | show the relative time |
| `searchRadius` | -1 | search field radius; `-1` = theme/2 |
| `modules` | `["brand","newSession","skinSwitcher","packManager","panels"]` | optional drawer modules; **sessions and settings are always rendered** |

Fixed anchors in the drawer: **会话列表** and **设置** are always rendered, regardless
of `drawer.modules` or `components.showSessions`. Their visuals follow the
`drawer.session*` and `drawer.button*` keys. Everything else (brand card, new
session, skin switcher, pack manager, panels/nav entries) can be added or
removed through `drawer.modules`.

`layout/chat.json` → `chat` appearance keys:

| Key | Default | Effect |
| --- | --- | --- |
| `paddingH` / `paddingV` | 14 / 10 | transcript content padding |
| `bottomPadding` | 16 | extra space below the last message |
| `messageSpacing` | 14 | vertical space between messages/tools |
| `emptyAvatarSize` | 64 | empty-state avatar dp |
| `codeBlockRadius` | -1 | fenced code block radius; `-1` = theme/2 |
| `codeBlockBorderAlpha` | 0.35 | fenced code block border opacity |

### Composer (`composer`)

| Key | Default | Effect |
| --- | --- | --- |
| `fontSize` | 0 | input text size in sp; `0` = theme bodyMedium |
| `textColor` | `onSurface` | input text token |
| `minHeight` | 0 | minimum input height; `0` = natural |
| `paddingH` / `paddingV` | 12 / 10 | composer inner padding |
| `radius` | -1 | composer top radius; `-1` = theme composerRadius/corner+8 |
| `inputRadius` | 22 | OutlinedTextField radius |
| `inputAlpha` | 0.35 | input background opacity |
| `borderAlpha` | 0.5 | unfocused input border opacity |
| `elevation` | -1 | composer shadow; `-1` = theme composerElevation |
| `surfaceAlpha` | 1 | composer surface opacity |
| `buttonIconSize` | 0 | send/stop icon size; `0` = 21dp |

`composer.placeholderColor`: `onSurfaceVariant` (default),
`primary`, `secondary`, `tertiary`, `text`, `error`.

### Chat bubbles (`chat.bubble`)

| Key | Default | Effect |
| --- | --- | --- |
| `paddingH` / `paddingV` | 14 / 11 | bubble inner padding |
| `borderWidth` | 1 | bubble border width; `0` removes it |
| `textColor` | `onSurface` | user bubble text token |
| `assistantBubble` | false | render assistant Markdown inside a surface card |
| `assistantBackgroundAlpha` | 0.55 | assistant card background opacity |
| `assistantBorderAlpha` | 0.25 | assistant card border opacity |
| `assistantTextColor` | `onSurface` | assistant card text token |

The user bubble gradient, radius, alpha, elevation and shadow still come from
`theme/colors.json`; `components.bubbleMaxWidthDp` still sets the width.

### Tool cards (`chat.toolCard`)

| Key | Default | Effect |
| --- | --- | --- |
| `background` | `surface` | `surface`, `surfaceVariant`, `background`, `transparent` |
| `backgroundAlpha` | 1.0 | card background opacity |
| `radius` | -1 | card radius; `-1` = theme `cornerRadius` |
| `borderWidth` | 1 | border width; `0` removes it |
| `borderAlpha` | -1 | `-1` = theme `cardBorderAlpha` |
| `padding` | 12 | expanded card padding |
| `collapsedPaddingH` / `collapsedPaddingV` | 11 / 8 | collapsed row padding |
| `iconBoxSize` | 26 | tool icon box dp |
| `iconSize` | 15 | tool icon dp |
| `iconRadius` | 8 | tool icon box radius |
| `iconAlpha` | 0.14 | tool icon background opacity |
| `titleSize` | 0 | tool name size; `0` = theme titleSmall |
| `metricsColor` | `onSurfaceVariant` | metrics text token |
| `chipRadius` | 6 | 收起/摘要/展开 chip radius |
| `chipAlpha` | 0.15 | selected chip background opacity |
| `groupRadius` | -1 | tool-group radius; `-1` = card radius |
| `groupPadding` | 10 | tool-group padding |

These only affect card/bubble visuals; tool execution, grouping and actions are
unchanged.

The existing `components.json` controls remain for visibility and line counts;
these new keys are for deeper visual tuning.

## Plan widgets

Extension widgets are rendered generically, except for the plan contract:
`plan` / `plan-todos` / `todos` keys with a JSON step list or a `☐/☑` text
checklist become a native card. The key grammar, the JSON shape and the fallback
rules live in [plan-widget.md](plan-widget.md).

## RPC data sources, bindings and actions

A pack can read PiG RPC data and run a small, safe set of RPC actions. The App
owns the RPC process and validates everything; a pack only declares names and
paths.

### dataSources

`layout/chat.json`:

```json
"dataSources": {
  "state": { "rpc": "get_state", "refresh": ["connect", "agent_end", "session_switch"] },
  "stats": { "rpc": "get_session_stats", "refresh": ["agent_end", "compaction_end"] },
  "commands": { "rpc": "get_commands", "refresh": ["connect", "agent_end"] },
  "models": { "rpc": "get_available_models", "params": {}, "refresh": ["pack_load"] },
  "entries": { "rpc": "get_entries", "params": { "since": "a1b2c3d4" }, "refresh": [] }
}
```

- `rpc` is a read-only command: `get_state`, `get_session_stats`,
  `get_commands`, `get_available_models`, `get_available_thinking_levels`,
  `get_messages`, `get_last_assistant_text`, `get_entries`, `get_tree`,
  `get_extensions`, `get_fork_messages`.
- `params` are copied into the RPC request.
- `refresh` triggers:
  - `connect` — relay hello
  - `agent_end` — after a turn
  - `compaction_end`
  - `session_switch` — new/switch session
  - `pack_load` — after a pack is applied
  - `window_open` — when a pack window/nav entry is opened
- Responses are stored under the source name and under the built-in aliases:
  `state`, `stats`, `commands`, `models`, `thinkingLevels`,
  `lastAssistantText`, `entries`, `tree`, `extensions`, `forkMessages`.
  `stats` also gets a computed `cacheHitRate` number.

### bindings

`bindings` maps built-in UI slots to a JSON path. Currently supported slots are
`header.title`, `header.subtitle`, `header.usage` and `header.cache`.

```json
"bindings": {
  "header.title": { "path": "state.sessionName", "fallback": "Aurora" },
  "header.subtitle": { "path": "state.model", "format": "{provider}/{id}" },
  "header.usage": { "path": "stats.contextUsage", "format": "{percent}% · {tokens}/{contextWindow}" },
  "header.cache": { "path": "stats", "format": "缓存 {cacheHitRate}%" }
}
```

- `path` is `source.key.key...`; numeric segments index arrays.
- `format` replaces `{value}` for a scalar or `{key}` / `{a.b}` for an object.
  Numbers are printed as integers when possible, otherwise with one decimal.
- `fallback` is used when the path is missing; if it is empty, the normal
  app fallback is used.

Window nodes can bind too:

```json
{ "type": "text", "bind": "stats.contextUsage", "format": "上下文 {percent}%" }
{ "type": "code", "bind": "entries", "format": "{value}", "maxLines": 20 }
```

### `context` bindings

`context` is not an RPC source: it is computed by the app from live state, so it
reflects the **effective** window (including a context length pinned in the
model picker) and keeps the last known usage while a relay is attached without a
live pig. Both header elements and window nodes can bind it.

| path | type | meaning |
| --- | --- | --- |
| `context` | object | whole object, for `format` templates |
| `context.percent` | number | usage percentage, `null` when unknown |
| `context.percentLabel` | string | e.g. `10.4%`, `—` when unknown |
| `context.tokens` | number | tokens currently in the context |
| `context.tokensLabel` | string | e.g. `104K` |
| `context.window` | number | effective max context length (0 when unknown) |
| `context.windowLabel` | string | e.g. `1M`, `128K`, `—` when unknown |
| `context.actualWindow` | number | window pig itself reports, before the pin |
| `context.actualWindowLabel` | string | e.g. `128K`, `—` when unknown |
| `context.pendingMark` | string | ` ⏳` while a pin waits for the pig restart that applies it, else `""` |
| `context.mismatch` | string | ` (pig 128K)` only when the pin and pig differ, else `""` |

```json
{ "type": "text", "bind": "context", "format": "ctx {percentLabel} / {windowLabel}{pendingMark}" }
```

That renders e.g. `ctx 10.4% / 1M`, and `ctx 57% / 1M (pig 128K)` while a pinned
window has not reached pig yet. Prefer it over `stats.contextUsage` when the pack
should follow a pinned window or survive a reconnect. A `dataSources` entry
named `context` takes precedence over the computed object.

### actions

`actions` defines named safe actions. A window button or nav entry calls
`pack:<name>`:

```json
"actions": {
  "compact": { "rpc": "compact", "confirm": "压缩上下文？" },
  "new": { "rpc": "new_session" },
  "rename": {
    "rpc": "set_session_name",
    "params": { "name": "{{form.name}}" }
  }
}
```

```json
{ "type": "button", "label": "压缩", "action": "pack:compact" }
{ "type": "button", "label": "新会话", "action": "pack:new" }
```

- Allowed action RPCs: `compact`, `new_session`, `abort`, `set_model`,
  `set_thinking_level`, `cycle_model`, `cycle_thinking_level`, `clear_queue`,
  `set_auto_compaction`, `set_auto_retry`, `abort_retry`, `set_session_name`.
- `confirm` opens a confirmation dialog before the action.
- `params` support `{{form.<field>}}` (form values from the same window) and
  `{{state.<path>}}` (current RPC data).
- `set_model` requires `provider` and `modelId`; `set_thinking_level` requires
  `level`; `set_session_name` uses `name`.
- Dangerous RPCs (`bash`, `switch_session`, `export_html`, `fork`, `clone`)
  are **not** available to packs yet; a future manifest allow-list plus
  confirmation can opt in.

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
| `showSessions` | bool | true | ignored: the recent-sessions section is always rendered |
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
  "skin": "palette",
  "check": "check", "search": "search", "history": "history",
  "chevronDown": "down", "chevronUp": "up"
}
```

Built-in names: `menu`, `send`, `stop`, `refresh`, `add`, `settings`, `build`
(alias `tool`), `check`, `delete`, `edit`, `more`, `search`, `copy`, `history`,
`down`, `up`, `close`, `warning`, `palette` (skin switcher), `folder`
(pack manager / import directory), `upload` (import ZIP).

## skins.json

Named theme overrides for the skin switcher. The base theme stays in
`theme/colors.json`; a skin only needs the fields it changes. Missing fields
inherit the base theme.

```json
{
  "default": "default",
  "skins": [
    { "id": "default", "name": "默认", "theme": {} },
    {
      "id": "neon",
      "name": "霓虹",
      "theme": {
        "primary": "#FF3DDC",
        "secondary": "#00E5FF",
        "accent": "#7C4DFF"
      }
    },
    {
      "id": "paper",
      "name": "纸白",
      "theme": {
        "background": "#F6F7FB", "surface": "#FFFFFF",
        "surfaceVariant": "#EDF0F6",
        "primary": "#6D5BD0", "accent": "#0891B2",
        "text": "#171A21", "textMuted": "#5E6778",
        "onPrimary": "#FFFFFF", "onAccent": "#FFFFFF"
      }
    }
  ]
}
```

- Every skin accepts the same keys as `theme/colors.json`.
- `default` selects the skin used when the saved choice is unavailable or no
  choice was made yet. If absent, the first skin is used. An empty/unknown id
  falls back to the first skin; with no `skins` the switcher stays hidden.
- The selection is persisted on the device (`skinId`) and applied on the next
  app start after the pack loads.
- Switch at runtime from the drawer/settings; the whole app recolors instantly,
  including already-open pack windows.

## Pack manager (importing packs)

Apart from the pack the relay pushes from `~/.pig/android-ui/`, the app can
hold several imported packs in its **private** directory:

```
/data/data/com.linuxwff.pigapp/files/ui-packs/<id>/
  manifest.json
  theme/colors.json
  ...
```

Open **UI pack 管理** from the drawer or settings (see `packManager` below).

- **内置 pack** — the live pack pushed by `relay_ui_pack` from
  `~/.pig/android-ui/`. Tapping it switches back after using an imported pack.
- **Import ZIP** — pick any `.zip` containing a pack (`manifest.json` at the
  root or inside a single top-level folder).
- **Import directory** — pick a folder with the system tree chooser.
- **Delete** — each imported pack has a delete icon in the manager. Deleting the
  active pack switches back to the relay pack first, then removes its files.

Import rules:

- Only files, no symlinks; each file must be ≤ 512 KB; the archive must contain
  `manifest.json`.
- Entries with `..`, absolute paths or a leading dot are skipped.
- The pack id is a slug of `manifest.name`; a numeric suffix is added when the
  id already exists.
- The selection is persisted (`activePackId`) and restored on the next start;
  an imported pack takes precedence over relay pushes until you switch back to
  the built-in pack.
- Switching a pack re-applies its theme, layout, strings, windows and assets.
  The `skins.json` selection is kept per pack (unknown skin ids fall back to the
  pack's `default`).

The relay's `~/.pig/android-ui/` directory is untouched by the manager; it is
still the source of the built-in pack and of hot reloads.

## layout/chat.json

```json
{
  "sidebar": { "widthDp": 300 },
  "chat": {
    "assistantLabel": "PIG",
    "toolCards": "collapsed",
    "toolGroups": true,
    "showUsage": true,
    "paddingH": 16, "paddingV": 12, "bottomPadding": 20,
    "messageSpacing": 16, "emptyAvatarSize": 72,
    "codeBlockRadius": 14, "codeBlockBorderAlpha": 0.5
  },
  "composer": {
    "placeholder": "给 Pig 发消息…",
    "maxLines": 6,
    "sendOnEnter": true
  },
  "skinSwitcher": {
    "show": true,
    "placement": "drawer",
    "style": "chips"
  },
  "packManager": {
    "show": true,
    "placement": "both"
  },
  "drawer": {
    "background": "gradient", "gradientAlpha": 0.22, "cornerRadius": 26,
    "paddingH": 18, "paddingV": 22, "spacing": 9,
    "brandAvatarSize": 48, "brandTagline": true, "brandCardAlpha": 0.42,
    "brandPills": true, "buttonHeight": 44, "buttonRadius": 14,
    "buttonIconSize": 18, "sectionLabelSize": 12,
    "sectionLabelUppercase": false, "sessionRowRadius": 16,
    "sessionSelectedAlpha": 0.18, "showSessionId": true,
    "showSessionTime": true, "searchRadius": 14
  },
  "dataSources": {
    "state": { "rpc": "get_state", "refresh": ["connect", "agent_end", "session_switch"] },
    "stats": { "rpc": "get_session_stats", "refresh": ["agent_end", "compaction_end"] },
    "last": { "rpc": "get_last_assistant_text", "refresh": ["agent_end"] }
  },
  "bindings": {
    "header.title": { "path": "state.sessionName", "fallback": "Pig" },
    "header.subtitle": { "path": "state.model", "format": "{provider}/{id}" },
    "header.usage": {
      "path": "stats.contextUsage",
      "format": "{percent}% · {tokens}/{contextWindow}"
    },
    "header.cache": { "path": "stats", "format": "缓存 {cacheHitRate}%" }
  },
  "actions": {
    "compact": { "rpc": "compact", "confirm": "压缩上下文？" },
    "new": { "rpc": "new_session" }
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
- `chat.showUsage` adds the context usage (percentage plus used/total tokens)
  and the cumulative cache hit rate to the top bar; the app refreshes it on
  connect, session switch, model change and after every turn. After compaction
  the header shows `已压缩` until the next model call provides a fresh usage.
- `composer.maxLines` is clamped to 1–12.
- `sendOnEnter` changes the IME action to Send.
- `skinSwitcher.show` hides the switcher entirely; it is also hidden when the
  pack has no `skins.json` entries.
- `skinSwitcher.placement`: `drawer` (default), `settings`, or `both`.
- `skinSwitcher.style`: `chips` (horizontal swatches, default) or `list`
  (full-width rows with a check mark).
- `packManager.show` hides the **UI pack manager** entry.
- `packManager.placement`: `drawer`, `settings`, or `both` (default).

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
