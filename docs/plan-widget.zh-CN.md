[English](plan-widget.md) · **简体中文**

# 计划组件（`setWidget` 契约，v1）

任何扩展都可以在 Android App 里把计划显示为原生清单卡片 —— 没有注册表、没有
plugin id，App 侧也没有白名单。App 靠**组件键（widget key）**识别 widget，并解析
它的**文本行**；不符合这份契约的一切内容，都继续保留原先的纯等宽文本 widget。

正因如此，用户才能自己挑选第三方计划扩展：App 从不需要知道装的是哪一个。

## 组件键

| 组件键（不区分大小写） | 含义 |
| --- | --- |
| `plan` | 规范键 |
| `plan-todos`, `plan_todos` | 别名，供 Pi 的 plan-mode 扩展移植版使用 |
| `todos`, `todo` | 别名 |

`widgetPlacement` 可以是 `aboveEditor`（默认）或 `belowEditor`；卡片与其他 widget
一样遵循这个值。

## 两种载荷形式

两者都走普通的 `setWidget` 请求，因此不需要现有
`extension_ui_request` 路径之外的任何东西：

```json
{"type":"extension_ui_request","method":"setWidget","widgetKey":"plan",
 "widgetLines":["…"],"widgetPlacement":"aboveEditor"}
```

### A. JSON 行（首选）

第一个非空行是一个 JSON 对象：

```json
{"plan":{"title":"Make the pin real","steps":[
  {"text":"Read agents.go","done":true},
  {"text":"Add relay_restart","done":true},
  {"text":"Write the docs","done":false}
]}}
```

- `plan` 可以省略（`{"title":…,"steps":[…]}` 也可接受）。
- `steps` 的条目可以是对象（`text`，可选的 `done`），也可以是纯字符串
  （视为未完成）。
- `title` 可选；没有它时 App 使用 pack 字符串 `plan.title`
  （随附的 pack 里是 `计划`）。
- 最多渲染 200 个步骤；超出部分被忽略。

JSON 行之后的多余行会被忽略，因此扩展可以附一条备注而不破坏卡片。

### B. 文本清单（兼容）

对于已经在终端里绘制 widget 的扩展 —— 包括 Pi
`examples/extensions/plan-mode` 的移植版 —— 纯文本行就够了：

```
Plan: Make the pin real
2/3
☑ Read agents.go
☑ Add relay_restart
☐ Write the docs
```

可识别的步骤语法（每行一个步骤）：

| 形式 | 示例 |
| --- | --- |
| 方框符号 | `☐ text`、`☑ text`、`✅ text`、`✔ text`、`✓ text` |
| Markdown 方框 | `- [ ] text`、`* [x] text`、`[ ] text` |
| 编号列表 | `1. text`、`2) text`（计为未完成） |
| 删除线表示完成 | `~~text~~` |

其他规则：

- 标题行只在**第一个步骤之前**被识别：
  `Plan: <text>`、`# Plan`、`📋 Plan: <text>`，或单独一个 `Plan`。
- 进度行（`2/5`、`📋 2/5`、`Plan 2/5`）会被接受但忽略 ——
  App 始终从步骤自行推导进度。
- 空行、备注及其他内容一律忽略。若识别不到任何步骤，widget 回退为纯文本面板
  （绝不会被丢弃）。

## 渲染

- 头部：标题 + `{done}/{total}`（pack 字符串 `plan.progress`）。
- 一条细进度条；全部完成时着色为 `tertiary`。
- 步骤：完成项为 `☑` + 删除线，未完成项为 `☐`；**第一个未完成步骤**用主色
  高亮。
- 只读：App 无法把某个步骤的切换回传给扩展，因为 widget 没有回传通道。扩展自己
  更新状态并发出新的 widget 行；点击只会让两边状态不一致。
- 列表在 180 dp 的框内滚动，因此再长的计划也不会把输入框挤出屏幕。

## 收起与关闭

两者都在 App 侧、本地完成；不会告知扩展（widget 没有回传通道）：

- 卡片头部的 **▾ / ▸** 把列表收起为一行 —— 标题、进度和当前步骤。该状态按
  组件键记忆，跨启动保留。
- **✕** 隐藏卡片。下次扩展发送*不同*内容时它会回来，所以隐藏永远不会吞掉真正的
  更新。`/plan clear`（或扩展自己的清除）会将其彻底移除。

切换会话时会清空关闭状态，因为 widget 属于写下它的那个会话的 agent。

## 状态行是独立的

plan-mode / 进度小标签不属于这份契约。扩展可以设置任意 `statusKey`
（`plan`、`plan-mode` ……），状态栏会原样显示在 App 自身的活动标签旁边。通用的
状态行为见 [ui-pack.zh-CN.md](ui-pack.zh-CN.md)。

## 版本演进

`v1` 是增量式的：可以新增组件键、新的可选 JSON 字段和新的步骤语法。删除或改变
现有形式的含义，则需要换另一个组件键的 `v2`。

## 从 shell 测试（debug 构建）

```bash
# JSON form
adb shell am broadcast -a com.linuxwff.pigapp.DEBUG_PLAN \
  -n com.linuxwff.pigapp/.DebugReceiver --es form json

# text form (Pi plan-mode style)
adb shell am broadcast -a com.linuxwff.pigapp.DEBUG_PLAN \
  -n com.linuxwff.pigapp/.DebugReceiver --es form text
```

两者都走真实的 `setWidget` 事件路径，并在 `PigDebug` 标签下打印解析结果。

任何扩展都能发出同样的东西。`widgetLines` 是字符串组成的 JSON 数组，其中第一个
元素就是 JSON 载荷：

```bash
adb shell am broadcast -a com.linuxwff.pigapp.DEBUG_EVENT \
  -n com.linuxwff.pigapp/.DebugReceiver \
  --es json '{"type":"extension_ui_request","method":"setWidget","widgetKey":"plan","widgetLines":["{\"plan\":{\"steps\":[{\"text\":\"one\",\"done\":true}]}}"]}'
```

## 该选哪个计划扩展

App 有意保持中立 —— 随便挑你喜欢的，也可以装几个、只启用一个：

- 本仓库的 `extensions/plan/` —— 可选的 Go 扩展，发出形式 A，并实现
  plan-mode（只读工具、`[DONE:n]`、执行提示词）。
- Pi 的 `packages/coding-agent/examples/extensions/plan-mode` —— TypeScript
  原版；它的 `☐/☑` widget 能被形式 B 原样接受。
- 你自己的 —— 只要用两种载荷形式之一设置计划组件键就能工作。

无需向 App 注册任何东西；不遵循契约的扩展也不受影响：它只是继续使用通用文本
widget。
