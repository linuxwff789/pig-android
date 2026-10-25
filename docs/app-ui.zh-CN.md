[English](app-ui.md) · **简体中文**

# `app-ui/1` — Pig 应用的声明式窗口

状态：**M3.4 已实现** — 全部四种摆放位置、组件集以及表单和表格、`builtin:` / `ext:` 动作、
`setWidget` 面板、`setTitle`、调试注入，以及具备热重载 / 增加-更新-删除 / 关闭抑制的文件通道。

目标：在不分发可执行 UI 代码的前提下获得类 TUI 的扩展能力：扩展（或 UI pack）写 JSON，
应用用 Compose 渲染。

## 窗口存放位置

```
~/.pig/android-ui/
  manifest.json
  theme/colors.json
  layout/chat.json
  windows/deploy.json      ← one file per window
  windows/status.json
```

- relay 已经会把该目录树中的每个文本文件（每个 ≤512 KiB）作为 `relay_ui_pack` 推送。
- 应用把每个匹配 `windows/*.json` 的 key 视为一个窗口定义。
- 删除文件即关闭窗口；编辑文件即热重载更新窗口。
- `id` 必填且必须唯一。ID 重复时：以最后一份文件为准（按路径排序），并显示一条警告。
- 解析失败的文件会保留该 `id` 上一份可用的窗口，并设置 `windowError`；其他窗口不受影响。

同一套 schema 之后还可以通过调试广播注入（无需文件），用于测试。

## 窗口对象

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

| 字段 | 默认值 | 说明 |
| --- | --- | --- |
| `appUI` | `1` | schema 版本；更高的未知版本会被拒绝 |
| `id` | 必填 | `[a-z0-9._-]{1,40}` |
| `title` | `id` | 显示在标题栏 |
| `placement` | `overlay` | `overlay`、`sheet`、`aboveComposer`、`belowComposer` |
| `width` / `height` | 320 / wrap | dp，会被钳制到屏幕范围内 |
| `dismissible` | `true` | 显示关闭按钮，并允许返回键关闭 |
| `autoOpen` | `true` | `false` 时窗口仍被缓存但保持关闭，直到导航条目或命令将其打开 |
| `body` | 必填 | 一个节点 |

窗口是全局的，不与会话绑定。切换会话时窗口保持打开。

## 节点

每个节点都支持 `type`、`visible`（默认 true）、`visibleWhen`
（`always/busy/idle/connected/offline/streaming/compacting`）、`padding`（dp），在
`row`/`column` 内部还支持 `weight`（0 = 自然尺寸）。文本与代码节点还接受
`bind` + `format`：`bind` 是指向当前 UI pack 的 RPC 数据的路径（例如
`stats.contextUsage`），`format` 是 `{key}` 模板。

容器：

| 类型 | 字段 |
| --- | --- |
| `column` | `children`、`spacing`、`align`：`start/center/end`、`scroll`：bool |
| `row` | `children`、`spacing`、`align`：`start/center/end` |
| `divider` | — |
| `spacer` | `size`（dp） |

内容：

| 类型 | 字段 |
| --- | --- |
| `text` | `text`、`style`：`body/heading/label/mono/muted/danger/markdown`、`align` |
| `code` | `text`、`language`、`maxLines`（默认 20） |
| `progress` | `value` 0..1，或 `indeterminate: true` |
| `button` | `label`、`action`、`style`：`primary/outline/text/danger`、`enabled` |

| `textfield` | `id`、`value`、`placeholder`、`maxLines` |
| `select` | `id`、`value`、`options`：`[{label,value}]` |
| `toggle` | `id`、`label`、`value`（bool） |
| `list` | `children`（项之间加分隔线） |
| `table` | `headers`、`rows`：字符串数组组成的数组 |
| `image` | `src`（`assets/...`）、`width`、`height`、`corner`、`alt` |
| `icon` | `icon`（来自 `icons.json`）、`size`、`color`、`action`、`description` |
| `avatar` | `size`、`action` |
| `connection` | `text` / `label` 可选；圆点反映连接状态 |

表单字段需要 `id`；它会成为随动作一起发送的 JSON key。缺少 `id` 时会生成 `fieldN`
并给出警告。

M3.5 可能加入 `webview`（默认关闭，仅限本地资源）。

未知类型会渲染一个小号 `[unsupported: type]` 占位符并追加一条警告；绝不会让窗口崩溃。

## 动作

```json
{ "type": "button", "label": "Retry", "action": "ext:deploy.retry" }
```

- `builtin:close` — 关闭承载该控件的窗口（M3.1）。
- `pack:<name>` — 运行 `layout/chat.json` 的 `actions` 中指定的安全动作；
  带 `confirm` 的动作会先弹出对话框。参见 `ui-pack.zh-CN.md`。
- `builtin:copy:<text>` — 把文本复制到剪贴板（M3.1）。
- `ext:<command>` — 去掉前缀，以 `/<command>` 发送 RPC `prompt`。
  命令名中的点、冒号、下划线和连字符已针对真实扩展验证过。表单值会作为一个紧凑的
  JSON 参数追加：
  `/uitest.submit {"name":"alice","env":"dev","verbose":"true"}`。
- `prompt:<text>` — 尚未实现（会绕过命令注册表）。

`ext:` 被刻意推迟，直到有测试扩展命令验证了命令名规则（`/skill:` 命名空间表明冒号是特殊字符）。

## 资源

`assets/` 下的二进制文件会以 base64（`relay_ui_pack.assets`）随 pack 推送，同时带上每个
文件的 sha256（`relay_ui_pack.assetHashes`）。应用在主线程之外解码，并按 `path@sha256`
缓存。每个文件限 512 KiB；修改图片会改变 pack 哈希，因此图片会像 JSON 文件一样热重载。

`src` 必须以 `assets/` 开头，且不得包含 `..`。

## 限制与校验

- 树深度 ≤ 32，节点数 ≤ 2000，单个字段文本 ≤ 16 KiB。
- 宽/高会被钳制到 200..屏幕宽/高。
- `padding`/`spacing` 会被钳制到 0..48。
- 非法值会带警告回退到默认值；非法 JSON 会拒绝整个窗口。

## 生命周期规则

1. **增加** — 新增 `windows/x.json`（或一次调试注入）会打开窗口。
2. **更新** — 相同 `id`、新内容：就地更新。拖拽位置、z 序和焦点都保留
   （UI 状态按 `id` 来 key，而非按节点树）。
3. **删除** — 文件消失：关闭窗口。
4. **关闭** — 用户关闭它：把 `id` 记为已关闭（dismissed）；文件仍然存在，因此后续 pack
   推送不得重新打开它。已关闭集合在应用进程启动时或文件被删除时清空。
5. **非法** — 保留上一份可用窗口，设置 `windowError`，并在抽屉中显示。
6. **切换会话** — 窗口保留；在扩展重写文件之前，其数据可能已过期。

## 扩展 widget 与标题

RPC `extension_ui_request` 现在的映射如下：

- `setWidget` → 位于输入框上方或下方的等宽面板，每个
  `widgetKey` 一个；不带 `widgetLines` 的请求会将其移除。
- `setTitle` → 顶栏标题（标题为空时重新显示 `Pig`）。
- `setStatus` / `notify` 行为与之前一致。

这些面板与 pack 窗口相互独立：它们是扩展设置的会话 UI 状态，而非文件，并且能在 UI pack
推送后继续存在。

## 为什么不直接用 `ctx.ui.custom()`？

RPC 模式的文档中，`custom()` 不返回任何组件，`setWidget()` 只接受字符串行。终端组件不可
序列化。一棵 JSON 树加一个渲染器就是可移植的等价方案，而且对非终端客户端同样适用。

## 为什么不先做 WebView？

WebView 面板可以渲染任何内容（HTML/JS/canvas），是最终的逃生通道，但它绕过了 pack 的
声明式保证，并引入 JS bridge 和攻击面。等原生组件集证明有用之后，再把它作为一种可选节点
类型保留。
