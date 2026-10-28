[English](ui-pack.md) · **简体中文**

# UI pack（M1）

UI pack 是一个由 JSON 文件组成的目录。`pig-relay` 监视
`~/.pig/android-ui/`（800 ms 轮询），对整棵树做哈希，并把整个 pack 推送给
App。App 立即应用 —— 无需重新构建，也无需重启。

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

`strings.json`、`components.json`、`icons.json` 和 `skins.json` 是可选文件，
按文件名自动发现（不需要 manifest 条目）。

从 `examples/ui-pack/` 开始：

```bash
mkdir -p ~/.pig/android-ui
cp -r examples/ui-pack/* ~/.pig/android-ui/
```

编辑任意文件，App 会在一秒内重新套用样式。

## pack 的来源

App 认识三种来源，顺序与 **设置 → UI pack 管理** 对话框中的显示一致：

| 来源 | 所在位置 | id | 说明 |
| --- | --- | --- | --- |
| relay（默认值） | Termux 中的 `~/.pig/android-ui/`，由 `pig-relay` 推送 | `""` | 每次变更都会自动生效，无需导入；初始内容来自 `examples/ui-pack-graphite/` |
| 内置 | APK 内的 `assets/ui-packs/<name>/` | `builtin:<name>` | 只读，离线时也会列出 |
| 导入 | App 私有目录中的 `files/ui-packs/<id>/` | `<id>` | 来自 导入 ZIP / 导入文件夹，可删除 |

内置 pack 随 APK 一起发布；第一个是 `builtin:compact`
（`examples/ui-pack-compact/`，构建前复制到
`app/src/main/assets/ui-packs/compact/`）。它和 relay 的默认 pack
（`examples/ui-pack-graphite/`）是两套不同的设计，所以管理器最上面两行是两个
明显不同的外观。它们不能删除 —— 只能选择 ——
并且直接从 `assets/` 读取，所以选择内置 pack 不会向 App 的 files 目录复制任何
内容。选择结果保存在 `activePackId` 中，因此能跨重启保留，并且在用户重新选择
内置 pack 之前一直优先于 relay 推送。

**首次启动** 会使用 `builtin:compact`，而不是在 Termux 尚未推送任何内容时显示
朴素界面；但这个默认值 *不是* 用户的选择：一旦 relay pack 到达，它就会接管并
清空 `activePackId`。在管理器中选中任意 pack（包括点击已选中的内置行）会设置
`packPinned`，此后 relay 推送会被忽略，直到用户再次选择内置 pack。

## manifest.json

```json
{
  "name": "Pig default",
  "version": 1,
  "theme": "theme/colors.json",
  "layout": "layout/chat.json"
}
```

`theme` 和 `layout` 是相对于 pack 根目录的路径。两者都是可选的；缺少文件时会
回退到内置默认值并报告一条警告。

## theme/colors.json

| Key | Type | 默认值 | 用途 |
| --- | --- | --- | --- |
| `background` | color | `#0A0C12` | App 背景 |
| `surface` | color | `#12151E` | 输入框、卡片、抽屉面板 |
| `surfaceVariant` | color | `#1A1F2B` | 工具卡片、胶囊按钮、只读横幅 |
| `primary` | color | `#A78BFA` | 标签、按钮、当前会话 |
| `secondary` | color | `#7DD3FC` | 工具图标、插队/待处理状态 |
| `accent` | color | `#22D3EE` | 品牌渐变终点（头像、发送按钮） |
| `text` | color | `#ECEEF5` | 正文文字 |
| `textMuted` | color | `#98A2B8` | 提示、元数据 |
| `danger` | color | `#FB7185` | 错误、停止按钮 |
| `cornerRadius` | int 0–40 | `18` | 卡片、气泡、面板 |
| `avatarShadow` | bool | `false` | 消息头像后方默认光晕 |
| `bubbleShadow` | bool | `true` | 用户消息气泡下方默认阴影 |
| `onPrimary` | color? | derived | 主色表面上的文字/图标颜色 |
| `onSecondary` | color? | derived | 次色表面上的文字/图标颜色 |
| `onAccent` | color? | derived | 强调色表面上的文字/图标颜色 |
| `outline` | color? | textMuted 40% | 边框与分隔线 |
| `outlineVariant` | color? | textMuted 18% | 淡分隔线 |
| `success` | color | `#4ADE80` | diff 新增行 / 成功 |
| `warning` | color | `#FBBF24` | 警告 |
| `scrim` | color | `#9E000000` | 对话框遮罩 |
| `bubbleStart` | color? | primary | 用户气泡渐变起点 |
| `bubbleEnd` | color? | accent | 用户气泡渐变终点 |
| `cardRadius` | int? | `cornerRadius` | 工具/卡片圆角覆盖 |
| `bubbleRadius` | int? | `cornerRadius+4` | 用户气泡圆角覆盖 |
| `chipRadius` | int? | `cornerRadius/3` | 胶囊按钮与小按钮 |
| `composerRadius` | int? | `cornerRadius+8` | 输入框表面圆角 |
| `composerElevation` | int 0–40 | `12` | 输入框阴影 |
| `bubbleElevation` | int 0–40 | `8` | 用户气泡阴影 |
| `fabElevation` | int 0–40 | `6` | 回到底部胶囊 |
| `bubbleStartAlpha` | float 0–1 | `0.30` | 气泡渐变起点 alpha |
| `bubbleEndAlpha` | float 0–1 | `0.16` | 气泡渐变终点 alpha |
| `bubbleBorderAlpha` | float 0–1 | `0.32` | 气泡边框 alpha |
| `cardBorderAlpha` | float 0–1 | `0.60` | 工具卡片边框 alpha |
| `chipAlpha` | float 0–1 | `0.15` | 选中胶囊按钮背景 alpha |

颜色接受 `#RGB`、`#RRGGBB` 和 `#AARRGGBB`（解析器也容忍缺少 `#`）。无效值会被
报告为警告，该字段回退到默认值；pack 的其余部分仍然生效。

### 主题热重载与优先级

每次哈希变化都会整体替换 pack。pack 之间不做部分合并：新文件中省略的键会回到
内置默认值。

`avatarShadow` 和 `bubbleShadow` 是 **默认值**，不是强制覆盖：

- 如果用户从未在 **设置** 里动过对应开关，就使用 pack 的值，修改
  `theme/colors.json` 会热重载它；
- 一旦用户切换过 消息头像阴影 / 气泡阴影，选择会存进 App 的 `pig`
  SharedPreferences（`avatarShadow`、`bubbleShadow`），并优先于 pack；
- 因此主题作者可以让主题带上（或关掉）阴影，同时单个用户仍能选择退出，而不必
  修改 pack。

App 重启后优先级相同：pack 提供默认值，已保存的偏好（如果存在）覆盖它。

## theme/typography.json

可选；由 `manifest.json` 用 `"typography": "theme/typography.json"` 指向它。
没有该文件时使用下面的默认值。

| Key | 默认值 | 用途 |
| --- | --- | --- |
| `body` | 15 | 助手/用户正文、Markdown 段落 |
| `label` | 11 | 状态、会话元数据、表头 |
| `h1`…`h6` | 21/19/17/15/14/13 | Markdown 标题与 App 标题 |
| `code` | 12 | 围栏代码块与行内代码 |
| `lineHeight` | 1.5 | 正文行高倍数 |
| `fontFamily` | `sans` | 正文/UI 字体：`sans`、`serif`、`mono`、`cursive` 或 `assets/fonts/x.ttf` |
| `headingFontFamily` | empty | 标题；为空时回退到 `fontFamily` |
| `labelFontFamily` | `mono` | 标签、元数据、分节标题 |
| `codeFontFamily` | `mono` | 代码块、行内代码、等宽节点 |
| `bodyWeight` | 400 | 正文字重（100–900 或 `normal`/`medium`/`bold`） |
| `headingWeight` | 600 | 标题字重 |
| `labelWeight` | 500 | 标签字重 |
| `letterSpacing` | 0 | 额外字距（sp），限制在 -2–4 |

字号单位为 sp，限制在 8–40；`lineHeight` 限制在 1.0–2.5。
这些值会传入 `PigTheme` 的 `Typography` 和 Markdown 渲染器，因此会和 pack 的
其余部分一起热重载。

### 自定义字体文件

把字体放在 `assets/fonts/` 下，并引用资源路径：

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

- 支持：`.ttf`、`.otf`、`.ttc`；每个文件 ≤512 KiB（relay 资源上限）。
- App 会把 `relay_ui_pack.assets` 中的字节解码到缓存，并用 Compose `Font(file)`
  加载；修改字体文件会改变 pack 哈希并触发热重载。
- 文件缺失或无效时回退到内置字体族名称。
- 内置名称：`sans`/`sans-serif`、`serif`、`mono`/`monospace`/`code`、
  `cursive`。

## assets/

`assets/` 下的二进制文件会在 pack 中以 base64 发送（每个文件 512 KiB），供
`image` 节点和 `fonts/` 文件使用：

```json
{"type":"image","src":"assets/logo.png","width":120,"height":120,"alt":"Pig logo"}
```

## nav.json

可选的抽屉条目。relay 会随 pack 的其余部分一起推送它。

```json
{
  "items": [
    {
      "id": "providers",
      "label": "模型供应商",
      "window": "native:providers",
      "command": "provider-ui",
      "icon": "build",
      "placement": "settings"
    }
  ]
}
```

- `window` 是缓存的 `windows/<id>.json` 的 id；点击该条目会恢复它，即使用户此前
  已关闭过。`native:providers`（以及旧的 `providers`）会打开 App 内置的模型
  供应商管理页——pack 只提供入口，界面由 App 自己实现。`native:` 前缀里的冒号是
  合法字符；window 非法会导致整项被丢弃并产生 pack 警告。
- `command` 是一个斜杠命令（开头的 `/` 可选）；它在窗口恢复后执行，以便扩展刷新
  该窗口。原生窗口不会执行它。
- `icon` 接受 `build`、`refresh` 或 `add`（未知值回退到 build）。
- `placement` 是 `drawer`（默认值，列在 面板 下）或 `settings`（渲染在 App 的
  设置对话框中，例如 模型供应商）。这只是数据：把条目在抽屉和设置之间移动不需要
  重新构建 App。
- 被 `nav.json` 引用的窗口是 **按需** 的：pack 加载时（包括 App 重启）不会自动
  打开；点击条目才会恢复它。没有 nav 条目的窗口在文件出现时仍会自动打开。

## strings.json

所有面向用户的标签、占位符、状态文案和无障碍描述。键可以是扁平或嵌套的；嵌套键
用点展平（`{"header":{"online":"在线"}}` → `header.online`）。缺失的键回退到
内置中文默认值，因此 pack 只需覆盖少量文案。

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

占位符用 `{name}` 形式，由 App 替换：

| 占位符 | 使用者 |
| --- | --- |
| `{percent}`, `{used}`, `{window}` | 头部用量 |
| `{rate}` | 头部 prompt 缓存命中率 |
| `{count}` | 待处理横幅、工具分组、行数 |
| `{tool}` | 工具分组头部 |
| `{name}` | 会话删除/切换对话框、pack 名称 |
| `{id}` | 抽屉会话胶囊、设置页会话信息、无窗口提示条 |
| `{file}` | 设置页会话信息 |
| `{title}` | 窗口关闭的无障碍标签 |
| `{bytes}` | 工具 write 指标 |
| `{code}` | bash 退出码指标、relay 退出 |
| `{added}`, `{removed}` | edit diff 指标 |
| `{label}` | 抽屉面板提示条 |
| `{action}` | 未知 `ext:` 动作提示条 |
| `{command}` | 斜杠命令提示条 |
| `{mode}` | 工具模式提示条 |
| `{hash}`, `{cwd}`, `{files}`, `{version}` | 设置「关于」 |
| `{error}` | relay/pack 错误信息 |

`empty.suggestions` 是用 `|` 分隔的列表。状态文案按 key 取用：
`thinking`、`working`、`compacting`、`retrying`、`switching`、`restoring`。

`header.cache` 是会话累计缓存命中率
（`cacheRead / (input + cacheRead + cacheWrite)`）。刚执行完 `/compact` 后 PiG
报告的 `contextUsage` 为 null；下一次模型调用给出新的 usage 值之前，头部会显示
`header.compacted`。

### 其余键分组

| 分组 | 使用者 |
| --- | --- |
| `header.*` | 标题、在线/离线、模型占位符、用量/缓存/已压缩 |
| `drawer.*`, `session.*` | 侧栏、会话徽标、重命名/删除/切换对话框 |
| `status.*`, `banner.*`, `composer.*` | 状态栏、横幅、输入区 |
| `tool.*` | 工具卡片、分组、指标模板、失败/运行中标签 |
| `markdown.*` | 代码围栏语言/行数与 收起/展开 |
| `model.*` | 模型选择器 |
| `settings.*` | 设置面板与关于/会话信息 |
| `commands.*` | 命令面板中的内置斜杠命令描述 |
| `setup.*` | 首次运行的 Termux 安装对话框 |
| `dialog.*` | 扩展的确认/输入/选择对话框（`confirmTitle`、`inputTitle`、`selectTitle`、`allow`、`deny`、`ok`、`cancel`，以及带 `{title}` 的 `pendingHidden` 和用于重新打开横幅的 `pendingOpen`） |
| `relay.*`, `error.*` | 启动器错误、连接错误、pack 错误 |
| `notification.*` | 前台服务通知（尽力而为：服务可能在 pack 加载前就启动，此时回退） |
| `a11y.*` | 菜单/停止/重连/复制/关闭/窗口关闭标签，以及 `image`、`attachImage`、`removeImage`、`saveImage` |
| `chat.imagesOmitted` | 因大小/数量上限被跳过的图片块的占位符（`{n}`） |
| `toast.imageLimit`, `toast.imageUnreadable`, `toast.modelNoImages` | 输入框图片反馈 |
| `toast.imageSaved`, `toast.imageSaveFailed` | 查看器保存反馈（`{path}`） |
| `brand.emoji` | 头像图标字形（默认值 🐷） |
| `skin.title`, `toast.skinChanged` | 皮肤切换器标题 / 确认提示 |
| `pack.*` | pack 管理对话框、内置/本地行、导入按钮 |
| `toast.packImported`, `toast.packImportFailed`, `toast.packMissing` | pack 管理反馈 |
| `pack.actionTitle`, `pack.actionConfirm`, `pack.actionMissing`, `pack.actionDenied`, `pack.actionNeedsModel`, `pack.actionNeedsLevel` | pack RPC 动作 |
| `toast.*` | 简短确认与校验错误 |

这些分组中的每个字面量都有内置中文回退，因此 pack 可以只发布它想改的键。命令面板
会把 `commands.*` 与 RPC `get_commands` 返回的扩展命令合并；新安装的扩展会在
`/reload-runtime`（或 agent 重启）之后、下一次面板刷新时出现。

## header

`header` 可选。省略它则保留内置头部。存在时它会替换整个顶栏，且每个元素都是显式
声明的。

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

容器键：

| Key | 默认值 | 说明 |
| --- | --- | --- |
| `show` | true | `false` 会完全移除头部 |
| `height` | 0 | 最小高度，单位 dp（0 = 由行内容包裹） |
| `paddingH` / `paddingV` | 12 / 2 | 内边距，会被限制范围 |
| `background` | `gradient` | `gradient`、`surface`、`transparent` |
| `gradientAlpha` | 0.13 | 仅用于 `gradient` |
| `divider` | false | 底部分隔线 |
| `body` | null | **像素级** appUI 节点树；存在时忽略 `rows` |
| `rows` | [] | 简单的行/元素列表（见下文） |

### 像素级头部（`header.body`）

需要完全自定义组合时，把 `appUI` 节点树放进 `header.body`。它使用与
`windows/*.json` 相同的节点（`column`、`row`、`text`、`code`、`progress`、
`button`、`textfield`、`select`、`toggle`、`list`、`table`、`image`、`divider`、
`spacer`、`icon`、`avatar`、`connection`），外加 `bind` / `format` 和
`visibleWhen`。

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

节点树中的头部动作：

| `action` | 效果 |
| --- | --- |
| `header:menu` | 打开抽屉 |
| `header:model` | 打开模型选择器 |
| `header:reconnect` | 重新连接 |
| `header:stop` / `header:abort` | 中止当前回合 |
| `header:settings` | 打开设置 |
| `header:new` / `header:new_session` | 新会话 |
| `pack:<name>` | 运行 `layout.actions` 条目 |

`builtin:copy:*` 和 `ext:*` 也能通过普通的窗口动作路径生效。

### 简单行（`header.rows`）

元素：

| `type` | 渲染内容 |
| --- | --- |
| `menu` | 抽屉菜单图标 |
| `brand` | 头像 + 标题 + 可选在线圆点 |
| `title` | 仅标题文字 |
| `model` | 模型标签 + 箭头；点击打开选择器 |
| `usage` | 上下文用量；默认 App 文案或 `bind`/`format` |
| `cache` | 缓存命中率；默认 App 文案或 `bind`/`format` |
| `connection` | 圆点 + 在线/离线 |
| `status` | 当前状态文案（`thinking`、`working`、…） |
| `stop` | 停止图标 |
| `reconnect` | 重连图标 |
| `settings` | 设置图标 |
| `newSession` | 新会话图标 |
| `icon` | 来自 `icons.json` 的任意内置图标 |
| `text` | 文字，支持 `bind`/`format` |
| `pill` | 半透明 `InfoPill` |
| `divider` | 垂直分隔线 |
| `spacer` | 弹性空白；`weight`（默认值 1） |

元素键：

| Key | 说明 |
| --- | --- |
| `visible` | 默认 true |
| `when` | `always`（默认值）、`busy`、`idle`、`connected`、`offline`、`streaming`、`compacting` |
| `size` | 图标/分隔线尺寸，单位 dp |
| `weight` | 行权重（0 = 自然尺寸） |
| `padding` | 外边距 |
| `icon` | 覆盖图标名称 |
| `label` / `text` | 静态回退文字 |
| `style` | `label`（默认值）、`body`、`heading`、`mono`、`muted`、`danger` |
| `color` | `onSurfaceVariant`（默认值）、`primary`、`secondary`、`tertiary`、`onSurface`、`error`、`success`、`warning` |
| `bind` + `format` | 读取 RPC 数据 |
| `action` | `menu`、`model`、`reconnect`、`stop`/`abort`、`settings`、`new`/`new_session`、`header:*`、`pack:<name>` |
| `avatar` / `avatarSize` / `online` | 仅用于 brand 元素 |
| `lines` | `text` 的最大行数（1–4，默认值 2）；对不能换行的标题用 `1` |

头部渲染在 `statusBarsPadding()` 内，因此 `paddingV` 是叠加在系统状态栏下方的。

## 抽屉 / 聊天外观

这些键只改变视觉效果：尺寸、间距、颜色、圆角和装饰性元素的可见性。它们不会重排
菜单动作，也不会改变消息收发行为。

`layout/chat.json` → `drawer`：

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `background` | `theme` | `theme`、`surface`、`gradient`、`transparent` |
| `gradientAlpha` | 0.18 | 抽屉渐变强度 |
| `cornerRadius` | theme | 抽屉面板圆角；`-1` = theme |
| `paddingH` / `paddingV` | 16 / 20 | 抽屉内容内边距 |
| `spacing` | 10 | 抽屉各区块之间的间距 |
| `brandAvatarSize` | 44 | 品牌头像 dp |
| `brandTagline` | true | 显示标语 |
| `brandCardAlpha` | 0.34 | 品牌卡片渐变/不透明度 |
| `brandPills` | true | 显示在线/模型/会话胶囊 |
| `buttonHeight` | 0 | 抽屉按钮最小高度；`0` = 自然尺寸 |
| `buttonRadius` | -1 | 抽屉按钮圆角；`-1` = theme |
| `buttonIconSize` | 16 | 抽屉按钮图标 dp |
| `sectionLabelSize` | 0 | 分节标题字号；`0` = 主题 label 字号 |
| `sectionLabelUppercase` | false | 分节标题大写 |
| `sessionRowRadius` | -1 | 会话行圆角；`-1` = theme |
| `sessionSelectedAlpha` | 0.13 | 选中会话背景 alpha |
| `showSessionId` | true | 显示 8 位会话 id |
| `showSessionTime` | true | 显示相对时间 |
| `searchRadius` | -1 | 搜索框圆角；`-1` = theme/2 |
| `modules` | `["brand","newSession","skinSwitcher","packManager","panels"]` | 可选的抽屉模块；**会话与设置始终渲染** |

抽屉中的固定锚点：**会话列表** 和 **设置** 始终渲染，与 `drawer.modules` 或
`components.showSessions` 无关。它们的外观遵循 `drawer.session*` 和
`drawer.button*` 键。其余一切（品牌卡片、新会话、皮肤切换器、pack 管理器、
面板/nav 条目）都可以通过 `drawer.modules` 增删。

`layout/chat.json` → `chat` 外观键：

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `paddingH` / `paddingV` | 14 / 10 | 对话内容内边距 |
| `bottomPadding` | 16 | 最后一条消息下方的额外空间 |
| `messageSpacing` | 14 | 消息/工具之间的垂直间距 |
| `emptyAvatarSize` | 64 | 空状态头像 dp |
| `codeBlockRadius` | -1 | 围栏代码块圆角；`-1` = theme/2 |
| `codeBlockBorderAlpha` | 0.35 | 围栏代码块边框不透明度 |

### 输入框（`composer`）

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `fontSize` | 0 | 输入文字字号（sp）；`0` = 主题 bodyMedium |
| `textColor` | `onSurface` | 输入文字颜色 token |
| `minHeight` | 0 | 输入最小高度；`0` = 自然尺寸 |
| `paddingH` / `paddingV` | 12 / 10 | 输入框内边距 |
| `radius` | -1 | 输入框顶部圆角；`-1` = 主题 composerRadius/corner+8 |
| `inputRadius` | 22 | OutlinedTextField 圆角 |
| `inputAlpha` | 0.35 | 输入背景不透明度 |
| `borderAlpha` | 0.5 | 未聚焦输入边框不透明度 |
| `elevation` | -1 | 输入框阴影；`-1` = 主题 composerElevation |
| `surfaceAlpha` | 1 | 输入框表面不透明度 |
| `buttonIconSize` | 0 | 发送/停止图标尺寸；`0` = 21dp |
| `attachTint` | `onSurfaceVariant` | 附件（图片）按钮的颜色 token |
| `attachBackground` | `none` | 其后方圆底；`none` 或颜色 token |
| `attachBackgroundAlpha` | 1 | 0–1 |
| `attachGap` | 4 | 附件按钮与输入框之间的 dp，0–24 |
| `attachIconSize` | 0 | `0` = `buttonIconSize`；0–48 |
| `inputSurface` | `surfaceVariant` | 输入文字背后的颜色 token |
| `cursorColor` | `primary` | 光标 token |
| `placeholderAlpha` | 0.75 | 0–1 |
| `sendStyle` | `brand` | `brand` 保留 primary→tertiary 渐变，`solid` 使用单一 token |
| `sendTint` | `onPrimary` | 发送图标着色 |
| `sendBackground` | `primary` | `sendStyle` 为 `solid` 时使用的 token |
| `sendGlow` | 8 | 发送圆按钮后方彩色阴影的 dp，0–24 |
| `stopBackground` | `error` | 停止圆按钮背后的 token |
| `stopBackgroundAlpha` | 0.16 | 0–1 |
| `stopTint` | `error` | 停止图标着色 |
| `thumbSize` | 56 | 待发送图片缩略图 dp，24–96 |
| `thumbRadius` | 10 | 缩略图圆角，0–40 |
| `thumbGap` | 6 | 缩略图之间的 dp，0–24 |
| `innerPaddingH` / `innerPaddingV` | -1 | 光标与输入边框之间的距离；`-1` = Material 的 16 dp |
| `marginH` / `marginV` | 0 | 输入框表面外侧的空间，0–48 / 0–32 |
| `maxWidth` | 0 | `0` = 全宽，否则输入框居中且最宽为该值（最大 1200） |
| `maxHeight` | 0 | 输入区域最大高度（dp），0 = 不限（最大 600） |
| `selectionColor` / `selectionAlpha` | `primary` / 0.4 | 文字选择背景 |
| `selectionHandleColor` | `primary` | 选择手柄 |
| `disabledSurface` / `disabledBorderAlpha` / `disabledTextColor` | `surfaceVariant` / 0.12 / `onSurfaceVariant` | 禁用状态下的输入框外观 |
| `errorBorderColor` / `errorTextColor` | `error` | 错误状态下的输入框外观 |
| `fontFamily` | `` | 仅覆盖输入框的 `typography.fontFamily`（`sans`、`serif`、`mono`、`cursive`、`assets/fonts/x.ttf`） |
| `lineHeight` | 0 | 相对输入字号的倍数；`0` 表示继承 |
| `letterSpacing` | 0 | sp，-2…8 |
| `textAlign` | `start` | `start` / `center` / `end` |
| `placeholderFontSize` | 0 | `0` = 与输入字号相同 |
| `sendDisabledBackground` / `sendDisabledTint` | `surfaceVariant` / `onSurfaceVariant` | 没有内容可发送时的发送圆按钮 |
| `sendShape` / `stopShape` / `attachShape` | `circle` | `circle` 或 `rounded` |
| `sendRadius` / `stopRadius` / `attachRadius` | -1 | `rounded` 时的圆角；`-1` = size/2.4 |
| `sendSize` / `stopSize` / `attachSize` | 0 | `0` = `components.composerButtonSize`，最大 72 |
| `sendPadding` / `stopPadding` / `attachPadding` | 0 | 用 2×padding 内缩字形（绘制尺寸 = 图标尺寸 − 2p，最小 6 dp） |
| `stopBorderWidth` / `stopBorderColor` | 0 / `error` | 停止圆按钮可选的外圈 |

### Markdown（`chat.markdown`）

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `linkColor` | `secondary` | `[text](url)` |
| `inlineCodeBackground` / `inlineCodeColor` | `surfaceVariant` / `onSurface` | `` `code` `` |
| `codeBlockBackground` / `codeBlockAlpha` | `background` / 0.6 | 围栏代码块 |
| `codeBlockBorderColor` | `outline` | 围栏代码块边框（不透明度来自 `chat.codeBlockBorderAlpha`） |
| `quoteTextColor` | `onSurfaceVariant` | `>` 引用块 |
| `tableHeaderBackground` | `surfaceVariant` | 表格表头行 |
| `tableBorderColor` / `tableBorderAlpha` | `outline` / 0.4 | 行分隔线 |
| `tableZebraAlpha` | 0 | 交替行叠加 `tableHeaderBackground` 的淡色，0 = 关闭 |
| `hrColor` | `outlineVariant` | `---` 与引用条 |

`composer.placeholderColor`：`onSurfaceVariant`（默认值）、`primary`、
`secondary`、`tertiary`、`text`、`error`。

上面每个 `*Color`、`*Tint` 和 `*Background` 都接受一个颜色 token：
`primary`、`secondary`、`tertiary`、`onPrimary`、`onSurface`、
`onSurfaceVariant`、`surface`、`surfaceVariant`、`background`、`outline`、
`error`、`success`、`warning`，以及 `none`（透明）。

### 聊天气泡（`chat.bubble`）

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `paddingH` / `paddingV` | 14 / 11 | 气泡内边距 |
| `borderWidth` | 1 | 气泡边框宽度；`0` 表示移除 |
| `textColor` | `onSurface` | 用户气泡文字 token |
| `assistantBubble` | false | 把助手 Markdown 渲染在表面卡片内 |
| `assistantBackgroundAlpha` | 0.55 | 助手卡片背景不透明度 |
| `assistantBorderAlpha` | 0.25 | 助手卡片边框不透明度 |
| `assistantTextColor` | `onSurface` | 助手卡片文字 token |
| `borderColor` | `primary` | 用户气泡边框 token |
| `assistantBorderColor` | `outline` | 助手卡片边框 token |
| `userBackground` | `brand` | `brand` 保留主题渐变；任意 token = 纯色填充 |
| `userBackgroundAlpha` | 1.0 | 纯色填充的不透明度 |
| `textAlign` | `start` | 用户气泡文字的 `start` / `center` / `end` |
| `radiusTL` / `radiusTR` / `radiusBR` / `radiusBL` | -1 | 逐角圆角；`-1` 继承主题/自动圆角 |

用户气泡的渐变色标、alpha、海拔与阴影仍来自 `theme/colors.json`；
`components.bubbleMaxWidthDp` 仍决定最大宽度。

### 工具卡片（`chat.toolCard`）

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `background` | `surface` | `surface`、`surfaceVariant`、`background`、`transparent` |
| `backgroundAlpha` | 1.0 | 卡片背景不透明度 |
| `radius` | -1 | 卡片圆角；`-1` = 主题 `cornerRadius` |
| `borderWidth` | 1 | 边框宽度；`0` 表示移除 |
| `borderAlpha` | -1 | `-1` = 主题 `cardBorderAlpha` |
| `padding` | 12 | 展开卡片的 padding |
| `collapsedPaddingH` / `collapsedPaddingV` | 11 / 8 | 收起行的 padding |
| `iconBoxSize` | 26 | 工具图标方框 dp |
| `iconSize` | 15 | 工具图标 dp |
| `iconRadius` | 8 | 工具图标方框圆角 |
| `iconAlpha` | 0.14 | 工具图标背景不透明度 |
| `titleSize` | 0 | 工具名称字号；`0` = 主题 titleSmall |
| `metricsColor` | `onSurfaceVariant` | 指标文字 token |
| `chipRadius` | 6 | 收起/摘要/展开 胶囊按钮圆角 |
| `chipAlpha` | 0.15 | 选中胶囊按钮背景不透明度 |
| `groupRadius` | -1 | 工具分组圆角；`-1` = 卡片圆角 |
| `groupPadding` | 10 | 工具分组 padding |
| `borderColor` | `outline` | 卡片边框 token |
| `errorBorderColor` | `error` | 工具失败时的边框 token |
| `titleColor` | `onSurface` | 工具名称 token |
| `iconTint` | `secondary` | 图标字形 token |
| `iconBackground` | `secondary` | 图标方框填充 token（不透明度来自 `iconAlpha`） |
| `diffAddColor` | `success` | diff 块中的 `+` 行 |
| `diffRemoveColor` | `error` | `-` 行 |
| `diffContextColor` | `onSurfaceVariant` | 未变更的行 |
| `codeFontFamily` | `""` | diff 字体族名称（见字体排版）；`""` = 代码字体 |
| `codeFontSize` | 0 | diff 文字字号；`0` = 11sp |

这些只影响卡片/气泡的视觉；工具的执行、分组和动作不变。

现有的 `components.json` 开关仍然负责可见性和行数控制；这些新键用于更细的视觉
调整。

### Chrome —— 状态栏、提示条、对话框、胶囊按钮（`chat.chrome`）

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `statusColor` | `onSurfaceVariant` | 状态胶囊文字 token |
| `statusSize` | 0 | 字号；`0` = 主题 labelSmall |
| `statusSpinnerColor` | `secondary` | 旋转指示器 token |
| `statusSpinnerSize` | 0 | 旋转指示器 dp；`0` = 11 |
| `statusBackground` | `surfaceVariant` | 胶囊 token；`none` = 透明 |
| `statusBackgroundAlpha` | 0.55 | 胶囊不透明度 |
| `statusPaddingH` / `statusPaddingV` | 12 / 6 | 胶囊内边距 |
| `toastContainer` | `""` | snackbar 背景 token；`""` = Material 默认值 |
| `toastText` | `""` | snackbar 文字 token |
| `toastAction` | `""` | snackbar 动作 token |
| `toastShape` | -1 | 圆角；`-1` = Material 默认值 |
| `toastInsetH` | 12 | 水平内缩 |
| `dialogSurface` | `""` | 提示框背景 token；`""` = Material 默认值 |
| `dialogRadius` | -1 | 提示框圆角 |
| `dialogMaxWidth` | 0 | 最大宽度（dp）；`0` = Material 默认值 |
| `dialogTitleColor` | `onSurface` | 对话框标题/图标 token |
| `dialogTextColor` | `onSurfaceVariant` | 对话框正文 token |
| `chipBackground` | `primary` | 选中过滤胶囊按钮的填充 token |
| `chipBackgroundAlpha` | 0.18 | 选中填充不透明度 |
| `chipLabelColor` | `primary` | 选中胶囊按钮的文字 token |
| `chipShape` | `circle` | `circle` 或 `rounded` |
| `chipRadius` | -1 | `chipShape` = `rounded` 时的圆角；`-1` = 10 |
| `emptyTitleColor` | `""` | 空状态标题 token；`""` 继承环境颜色 |

### 思考（`chat.thinking`）

助手回答上方的推理块。它只影响显示：收起、展开和字数统计都不会改变模型看到或执行
的内容。

| Key | 默认值 | 效果 |
| --- | --- | --- |
| `show` | `true` | `false` 会完全隐藏思考块（与 pig 的 `hideThinkingBlock` 效果相同） |
| `collapsedByDefault` | `true` | 初始为收起状态 |
| `expandWhileStreaming` | `true` | 内容仍在流式输出时展开；用户点击优先于该设置 |
| `showCount` | `true` | 在标题旁显示 `N 字` |
| `title` | `""` | 覆盖 `chat.thinking` 文案（`思考`） |
| `fontSize` | `0` | 正文字号；`0` = App 的 body-small |
| `italic` | `false` | 正文斜体 |
| `background` | `surfaceVariant` | `surface`、`surfaceVariant`、`background`、`transparent` |
| `backgroundAlpha` | 0.42 | 背景不透明度 |
| `titleColor` | `secondary` | 标题使用的主题 token |
| `textColor` | `onSurfaceVariant` | 字数、字形和正文使用的主题 token |
| `radius` | -1 | 块圆角；`-1` = 10dp |
| `paddingH` / `paddingV` | 10 / 7 | 头部 padding（正文复用 `paddingH`） |
| `maxHeight` | 240 | 正文可滚动高度上限（dp） |
| `collapsedGlyph` / `expandedGlyph` | `▸` / `▾` | 头部箭头 |

```json
"chat": {
  "thinking": {
    "collapsedByDefault": true,
    "expandWhileStreaming": true,
    "showCount": true,
    "background": "surfaceVariant",
    "backgroundAlpha": 0.42,
    "italic": true,
    "fontSize": 12,
    "maxHeight": 240
  }
}
```

有两个开关都能隐藏该块，而且都是刻意保留的：`chat.thinking.show`（pack 的选择）
和 `settings.json` 中的 `hideThinkingBlock`（用户的选择，也可从
**设置 → 会话行为 → 显示思考过程** 切换）。任意一个都会隐藏它；不存在某个开关能
强制它显示的状态。

## 计划 widget

扩展 widget 采用通用渲染，但计划约定例外：值为 JSON 步骤列表或 `☐/☑` 文本清单的
`plan` / `plan-todos` / `todos` 键会变成原生卡片。键的语法、JSON 结构和回退规则见
[plan-widget.zh-CN.md](plan-widget.zh-CN.md)。

## RPC 数据源、绑定与动作

pack 可以读取 PiG RPC 数据，并执行一小组安全的 RPC 动作。App 拥有 RPC 进程并校验
一切；pack 只声明名称和路径。

### dataSources

`layout/chat.json`：

```json
"dataSources": {
  "state": { "rpc": "get_state", "refresh": ["connect", "agent_end", "session_switch"] },
  "stats": { "rpc": "get_session_stats", "refresh": ["agent_end", "compaction_end"] },
  "commands": { "rpc": "get_commands", "refresh": ["connect", "agent_end"] },
  "models": { "rpc": "get_available_models", "params": {}, "refresh": ["pack_load"] },
  "entries": { "rpc": "get_entries", "params": { "since": "a1b2c3d4" }, "refresh": [] }
}
```

- `rpc` 是一个只读命令：`get_state`、`get_session_stats`、
  `get_commands`、`get_available_models`、`get_available_thinking_levels`、
  `get_messages`、`get_last_assistant_text`、`get_entries`、`get_tree`、
  `get_extensions`、`get_fork_messages`。
- `params` 会原样放进 RPC 请求。
- `refresh` 触发时机：
  - `connect` —— relay 握手
  - `agent_end` —— 一个回合结束后
  - `compaction_end`
  - `session_switch` —— 新建/切换会话
  - `pack_load` —— pack 应用之后
  - `window_open` —— 打开 pack 窗口/nav 条目时
- 响应会以数据源名称以及内置别名保存：
  `state`、`stats`、`commands`、`models`、`thinkingLevels`、
  `lastAssistantText`、`entries`、`tree`、`extensions`、`forkMessages`。
  `stats` 还会额外得到一个计算出的 `cacheHitRate` 数字。

### bindings

`bindings` 把内置 UI 槽位映射到 JSON 路径。当前支持的槽位是 `header.title`、
`header.subtitle`、`header.usage` 和 `header.cache`。

```json
"bindings": {
  "header.title": { "path": "state.sessionName", "fallback": "Aurora" },
  "header.subtitle": { "path": "state.model", "format": "{provider}/{id}" },
  "header.usage": { "path": "stats.contextUsage", "format": "{percent}% · {tokens}/{contextWindow}" },
  "header.cache": { "path": "stats", "format": "缓存 {cacheHitRate}%" }
}
```

- `path` 形如 `source.key.key...`；数字段用于索引数组。
- `format` 对标量替换 `{value}`，对对象替换 `{key}` / `{a.b}`。数字尽可能按整数
  打印，否则保留一位小数。
- 路径缺失时使用 `fallback`；若它为空，则使用 App 的常规回退。

窗口节点也可以绑定：

```json
{ "type": "text", "bind": "stats.contextUsage", "format": "上下文 {percent}%" }
{ "type": "code", "bind": "entries", "format": "{value}", "maxLines": 20 }
```

### `context` 绑定

`context` 不是 RPC 数据源：它由 App 根据实时状态计算，因此反映 **生效的** 窗口
（包括在模型选择器中钉住的上下文长度），并且在 relay 已连接但没有活的 pig 时保留
最后一次已知用量。头部元素和窗口节点都可以绑定它。

| path | 类型 | 含义 |
| --- | --- | --- |
| `context` | object | 整个对象，用于 `format` 模板 |
| `context.percent` | number | 用量百分比，未知时为 `null` |
| `context.percentLabel` | string | 例如 `10.4%`，未知时为 `—` |
| `context.tokens` | number | 当前上下文中的 token 数 |
| `context.tokensLabel` | string | 例如 `104K` |
| `context.window` | number | 生效的最大上下文长度（未知时为 0） |
| `context.windowLabel` | string | 例如 `1M`、`128K`，未知时为 `—` |
| `context.actualWindow` | number | pig 自己上报的窗口（钉住之前） |
| `context.actualWindowLabel` | string | 例如 `128K`，未知时为 `—` |
| `context.pendingMark` | string | 钉住的窗口等待 pig 重启生效期间为 ` ⏳`，否则为 `""` |
| `context.mismatch` | string | 仅当钉住值与 pig 不同时为 ` (pig 128K)`，否则为 `""` |

```json
{ "type": "text", "bind": "context", "format": "ctx {percentLabel} / {windowLabel}{pendingMark}" }
```

这样会渲染出例如 `ctx 10.4% / 1M`，而在钉住的窗口尚未到达 pig 时渲染为
`ctx 57% / 1M (pig 128K)`。当 pack 需要跟随钉住的窗口或在重连后仍然可信时，优先
用它而不是 `stats.contextUsage`。名为 `context` 的 `dataSources` 条目优先于计算
出的对象。

### `session` 绑定

`session` 描述 App 所附着的会话。它的名称只会随 `get_sessions` 到达，因此由 App
计算，而不是作为 RPC 数据源暴露。

| path | 类型 | 含义 |
| --- | --- | --- |
| `session` | object | 整个对象，用于 `format` 模板 |
| `session.title` | string? | 会话名称，否则扩展 `setTitle`，否则首条消息预览；未知时为 `null`，此时使用元素的 `text` 回退 |
| `session.name` | string? | 来自 `/rename` 的名称，会话没有名称时为 `null` |
| `session.preview` | string | 首条消息预览 |
| `session.path` | string | 会话文件路径 |
| `session.id` | string | pig 显示的会话 id |
| `session.live` / `session.busy` | boolean | 是否已附着 pig 进程 / 是否正在产出一个回合 |

```json
{ "type": "text", "bind": "session.title", "text": "新会话", "style": "heading", "lines": 1 }
```

`session.title` 在进入头部之前会被截断到 120 个字符；如果 pack 需要未截断的首条
消息，请用 `session.preview`。与 `context`、`stats` 一样，名为 `session` 的
`dataSources` 条目优先于计算出的对象。

### `stats` 绑定

与 `context` 类似，`stats` 由 App 计算，因此 pack 无需声明数据源就能显示会话总计。
它来自最后一次 `get_session_stats` 回复，覆盖 **整个会话文件**（不只是 App 加载的
2000 条消息）。

| path | 类型 | 含义 |
| --- | --- | --- |
| `stats` | object | 整个对象，用于 `format` 模板 |
| `stats.input` / `stats.output` | number | 累计 prompt / completion token |
| `stats.cacheRead` / `stats.cacheWrite` | number | prompt 缓存计数器 |
| `stats.total` | number | pig 上报的合计 |
| `stats.*Label` | string | 上述五个计数器的 `58.9M`、`1.6M` 等 |
| `stats.cacheRate` | number | 缓存命中占比，未知时为 `null` |
| `stats.cacheRateLabel` | string | 例如 `88%` |
| `stats.cost` | number | 累计费用（美元） |
| `stats.costLabel` | string | 例如 `$0.0613`，为零时为 `—` |
| `stats.userMessages`, `stats.assistantMessages`, `stats.toolCalls`, `stats.toolResults`, `stats.totalMessages` | number | 消息计数器 |
| `stats.summary` | string | `58.9M in · 1.6M out · $0.0613` |

```json
{ "type": "text", "bind": "stats", "format": "{summary}" }
```

名为 `stats` 的 `dataSources` 条目优先，和 `context` 完全一样；这种情况下 pack
看到的是 pig 的原始回复（`tokens`、`contextUsage`、`cost`、各类计数器），而不是
计算出的标签。

**设置 → 数据 → 使用统计** 对话框会显示同样的数字。

### actions

`actions` 定义具名的安全动作。窗口按钮或 nav 条目通过 `pack:<name>` 调用它们：

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

- 允许的动作 RPC：`compact`、`new_session`、`abort`、`set_model`、
  `set_thinking_level`、`cycle_model`、`cycle_thinking_level`、`clear_queue`、
  `set_auto_compaction`、`set_auto_retry`、`abort_retry`、`set_session_name`。
- `confirm` 会在执行动作前打开确认对话框。
- `params` 支持 `{{form.<field>}}`（来自同一窗口的表单值）和
  `{{state.<path>}}`（当前 RPC 数据）。
- `set_model` 需要 `provider` 和 `modelId`；`set_thinking_level` 需要
  `level`；`set_session_name` 使用 `name`。
- 危险 RPC（`bash`、`switch_session`、`export_html`、`fork`、`clone`）目前
  **不对 pack 开放**；将来通过 manifest 白名单加确认可以按需开启。

## components.json

内置组件的布尔/尺寸开关。所有键都是可选的且会被限制范围；下面的值是默认值。

| Key | Type | 默认值 | 效果 |
| --- | --- | --- | --- |
| `showHeaderAvatar` | bool | true | 头部品牌头像 |
| `headerAvatarSize` | int 16–96 | 36 | 头部头像 dp |
| `showHeaderOnline` | bool | true | 在线圆点 + 标签 |
| `showDrawerBrand` | bool | true | 抽屉头部卡片 |
| `showPanels` | bool | true | nav 面板区块 |
| `showSessions` | bool | true | 已忽略：最近会话区块始终渲染 |
| `showSessionBadges` | bool | true | 运行中/已打开/开过/历史 徽标 |
| `showSessionSize` | bool | true | 会话行中的 `· 123K` |
| `showCopy` | bool | true | 每条消息的复制按钮 |
| `showSelection` | bool | true | 长按文字选择 |
| `showSuggestions` | bool | true | 空状态建议胶囊按钮 |
| `showReadOnlyBanner` | bool | true | 历史会话横幅 |
| `showPendingBanner` | bool | true | 插队/待处理横幅 |
| `showSteerHint` | bool | true | “点发送=插队”提示 |
| `showStatusBar` | bool | true | 瞬时状态栏 |
| `assistantAvatar` | bool | true | 助手消息旁的头像 |
| `messageAvatarSize` | int 12–64 | 24 | 消息头像 dp |
| `bubbleMaxWidthDp` | int 200–1200 | 520 | 用户气泡最大宽度 |
| `toolSummaryLines` | int 1–20 | 4 | 摘要模式的行数上限 |
| `toolExpandedLines` | int 20–2000 | 200 | 展开工具输出的上限 |
| `codeFenceMaxLines` | int 20–2000 | 400 | Markdown 围栏代码块上限 |
| `tableMaxLines` | int 1–20 | 6 | Markdown 表格单元格上限 |
| `composerButtonSize` | int 32–72 | 46 | 发送/停止按钮 dp |

## icons.json

把组件 key 映射到内置图标名称。未知名称回退到组件默认值；未知 key 会被忽略。

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

内置名称：`menu`、`send`、`stop`、`refresh`、`add`、`settings`、`build`
（别名 `tool`）、`check`、`delete`、`edit`、`more`、`search`、`copy`、`history`、
`down`、`up`、`close`、`warning`、`palette`（皮肤切换器）、`folder`
（pack 管理器 / 导入目录）、`upload`（导入 ZIP）。

## skins.json

供皮肤切换器使用的具名主题覆盖。基础主题仍在 `theme/colors.json` 中；皮肤只需要写
它改动的字段。缺失字段继承基础主题。

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

- 每个皮肤接受与 `theme/colors.json` 相同的键。
- `default` 指定在保存的选择不可用或尚未做出选择时使用的皮肤。若缺失，则使用第一个
  皮肤。空/未知 id 回退到第一个皮肤；没有 `skins` 时切换器保持隐藏。
- 选择会持久化在设备上（`skinId`），并在 pack 加载后的下一次 App 启动时应用。
- 可在运行时从抽屉/设置切换；整个 App 立即换色，包括已打开的 pack 窗口。

## pack 管理器（导入 pack）

除了 relay 从 `~/.pig/android-ui/` 推送的 pack 之外，App 还可以在自己的 **私有**
目录中保存多个导入的 pack：

```
/data/data/com.linuxwff.pigapp/files/ui-packs/<id>/
  manifest.json
  theme/colors.json
  ...
```

从抽屉或设置打开 **UI pack 管理**（见下文 `packManager`）。

- **内置 pack** —— 由 `relay_ui_pack` 从 `~/.pig/android-ui/` 推送的活动 pack。
  在使用导入 pack 后点击它即可切回。
- **导入 ZIP** —— 选择任意包含 pack 的 `.zip`（`manifest.json` 位于根目录或单一
  顶层文件夹内）。
- **导入文件夹** —— 用系统目录选择器选一个文件夹。
- **删除** —— 每个导入的 pack 在管理器中都有删除图标。删除活动 pack 时会先切回
  relay pack，然后删除它的文件。

导入规则：

- 只允许文件，不允许符号链接；每个文件必须 ≤ 512 KB；压缩包必须包含
  `manifest.json`。
- 含 `..`、绝对路径或以点开头的条目会被跳过。
- pack id 是 `manifest.name` 的 slug；当 id 已存在时会追加数字后缀。
- 选择会被持久化（`activePackId`）并在下次启动时恢复；在你切回内置 pack 之前，
  导入的 pack 优先于 relay 推送。
- 切换 pack 会重新应用它的主题、布局、文案、窗口和资源。`skins.json` 的选择按
  pack 分别保存（未知皮肤 id 回退到该 pack 的 `default`）。

relay 的 `~/.pig/android-ui/` 目录不会被管理器修改；它仍然是内置 pack 和热重载的
来源。

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
    "sendOnEnter": true,
    "attachTint": "primary", "attachGap": 6,
    "inputSurface": "surface", "cursorColor": "tertiary",
    "sendStyle": "solid", "sendBackground": "primary", "sendGlow": 0,
    "stopBackground": "warning", "stopBackgroundAlpha": 0.24,
    "thumbSize": 64, "thumbRadius": 14, "thumbGap": 8
  },
  "settings": {
    "defaultSize": "standard",
    "sizes": [
      { "id": "compact", "label": "紧凑", "width": 0.8, "height": 0.64 },
      { "id": "standard", "label": "标准", "width": 0.94, "height": 0.86 },
      { "id": "large", "label": "大", "width": 0.99, "height": 0.96 }
    ]
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

- `sidebar.widthDp` 限制在 240–560。
- `chat.toolGroups`：为 `true`（默认值）时，连续且工具名相同的工具卡片会折叠进一个
  分组头部（`bash × 5`）。分组默认收起；展开后显示各个卡片，而这些卡片本身又会按
  `toolCards` 收起 —— 这形成两层收起，使长串工具调用保持低渲染开销。设为 `false`
  则直接渲染每张工具卡片。
- `chat.toolCards`：初始工具卡片模式：
  - `collapsed`（默认值）—— 单行显示工具名；点击打开 摘要；
  - `collapsible` —— 从 摘要 开始（标题行、指标、前几行）；
  - `expanded` —— 完整输出。
  展开的卡片有 收起 / 摘要 / 展开 胶囊按钮；长按某个胶囊按钮会把该模式应用到所有
  工具卡片。`write` 报告行数和字节数，`edit` 报告新增/删除的行数，并用带色的 +/-
  行渲染 diff。
- `chat.showUsage` 会在顶栏加入上下文用量（百分比加已用/总量 token）和累计缓存
  命中率；App 在连接、切换会话、模型变更以及每个回合结束后刷新它。压缩之后，头部会
  显示 `已压缩`，直到下一次模型调用给出新的用量。
- `composer.maxLines` 限制在 1–12。
- `sendOnEnter` 会把输入法动作改为 Send。
- `settings` 决定 **设置对话框** 的尺寸。每个条目是用户可在 设置 → 窗口大小 中选择的
  预设；比例相对于可用屏幕，并限制在 0.3–1。
  - `sizes[]`：`id`（必填，唯一）、`label`（回退到 pack 文案
    `settings.size.<id>`，再回退到 id）、`width`、`height`。
  - `defaultSize`：用户选择之前使用的预设（未知 id 会警告并回退到第一个预设）。
  - `showSizeSwitcher`：`false` 会隐藏胶囊按钮；声明了多于一个尺寸时默认为 `true`。
  - 只给 `width`/`height`（不给 `sizes`）会钉死单一尺寸并隐藏胶囊按钮 —— 这是让
    对话框始终全屏或始终窄屏的做法。
  - 用户的选择保存在设备上（`settingsSize`），而不是 pack 中。重命名或删除 id 只会
    回退到 pack 默认值。
- `skinSwitcher.show` 完全隐藏切换器；当 pack 没有 `skins.json` 条目时它也会隐藏。
- `skinSwitcher.placement`：`drawer`（默认值）、`settings` 或 `both`。
- `skinSwitcher.style`：`chips`（横向色块，默认值）或 `list`（带勾选标记的整行）。
- `packManager.show` 隐藏 **UI pack 管理器** 条目。
- `packManager.placement`：`drawer`、`settings` 或 `both`（默认值）。

未知键会被忽略。文件中 JSON 无效时会产生一条警告，该文件的字段保留上一次的有效值，
pack 的其余部分仍然应用。只有 `manifest.json` 损坏会导致整个 pack 被拒绝。

## 线上格式

relay 每次变更发送一帧：

```json
{"type":"relay_ui_pack","hash":"<sha256>","files":{"manifest.json":"…","theme/colors.json":"…"}}
```

`hash` 覆盖排序后的 `path\0content\0` 对。App 把解析后的 pack 保存在
`AppUiState.uiPack` 中，因此 `DEBUG_STATE` 显示的是生效值（颜色以 Compose
`Color(r,g,b,a)` 打印）。
