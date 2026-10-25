[English](app-ui-plan.md) · **简体中文**

# M3 实施计划 — 声明式窗口

目标：提供一个类 TUI 的界面，任何扩展只要写 JSON 就能打开、更新和关闭窗口，而无需向手机
分发代码。

约定：[`docs/app-ui.zh-CN.md`](app-ui.zh-CN.md)。本文件给出执行顺序、验收标准和注意事项清单。

## 基本规则

- 一次只做一个里程碑：实现 → CI 绿 → 安装 → 从 shell 验证 → 提交。上一个里程碑未在
  设备上验证通过之前，不开始下一个里程碑。
- 每项新能力都要有 **调试钩子**（`DEBUG_WINDOW`、`DEBUG_ACTION`、
  `DEBUG_STATE`），让验证永远不依赖触摸、logcat 或截图。
- JSON 解析是全量的：坏输入只会降级为警告 + 上一个可用状态，绝不崩溃。这是实际使用中
  最可能出问题的性质。
- 不要改动已有的 `relay_ui_pack` 传输格式。窗口走同一个 `files` map，位于 `windows/` 下。

## M3.1 — 渲染器 + 浮层 + 调试注入 — DONE

已在设备上验证：注入一个 340×460 的窗口（`text`、`muted`、`progress`、
`code`、`button`），状态显示
`windows=[AppWindow(id=demo, title=Deploy, 340x460)]`；`DEBUG_CLOSE_WINDOW`
将其清空；非法 JSON 会设置 `windowError` 且不影响应用。视觉确认仍待完成，因为该设备上的
`uiautomator dump` 开始返回 `null root node`；状态文件断言是回退手段。

范围：

- `AppWindow` / `UiNode` 模型与解析器（`app-ui/1`）、深度/节点数限制。
- 组件：`column`、`row`、`divider`、`spacer`、`text`、`code`、`progress`、
  `button`。
- 一种 `overlay` 摆放位置：浮动卡片、标题栏、拖拽、关闭、焦点/z 序。
- `builtin:close` 与 `builtin:copy:<text>`。
- 调试：`DEBUG_WINDOW --es json '<window>'`、`DEBUG_CLOSE_WINDOW --es id`，
  状态暴露 `windows=[{id,title,placement}]` 与 `windowError`。

验收标准：

- 注入一个包含全部八个组件的窗口；它渲染在聊天之上。
- 拖拽只在屏幕边界内移动；旋转后仍然可见。
- 关闭后隐藏；以相同 id 再次 `DEBUG_WINDOW` 会重新打开/更新它。
- 非法 JSON → `windowError`，无窗口出现，应用仍可用。
- 返回键先关闭最上层窗口，再退出应用。

## M3.2 — 文件通道 + 热重载 — DONE

已用 `windows/deploy.json` 在设备上验证：文件在状态中显示为
`AppWindow(id=deploy, title=Deploy v1, 340x440)`；编辑后无需重启即变为
`Deploy v2, 340x500`；关闭它之后再编辑仍保持关闭；删除文件会清空关闭记录，重新添加后
又打开了 `Deploy v4`；损坏的 JSON 保留了上一个可用窗口并设置
`windowError=windows/deploy.json: JSON 解析失败…`。

范围：

- 从匹配 `windows/*.json` 的 `relay_ui_pack` key 派生窗口。
- 每次 pack 都做协调：增加、更新（保留位置/z 序）、删除。
- 已关闭集合：用户关闭的窗口在下次 pack 推送时不会重新打开；
  在进程启动或文件被删除时清空。
- 每个窗口的解析错误会保留上一个可用窗口并设置 `windowError`。

验收标准：

- `echo ... > ~/.pig/android-ui/windows/demo.json` → 窗口在 <2 s 内出现。
- 只编辑 body 会就地更新；拖拽位置保持不变。
- 删除文件即关闭窗口。
- 写入损坏的 JSON 会保留旧窗口并暴露错误。
- 窗口打开期间，主题/布局的编辑仍然生效。

## M3.3 — 动作 + 表单 — DONE

用一个一次性扩展验证了宿主的命令名规则：`nt.dot`、
`nt:colon`、`nt_underscore`、`nt-dash` 都能分发并收到各自的参数。
端到端验证：一个含 `textfield`/`select`/`toggle`/`table` 的 pack 窗口，值通过调试设置，
Submit 按钮（`ext:uitest.submit`）在扩展日志中产生了
`{"name":"alice","env":"dev","verbose":"true"}`。测试扩展及其设置项随后已删除。

范围：

- `ext:` 动作 → 以 `/<command>` 发送 RPC `prompt`；先用一个一次性 Go 扩展验证命令名规则。
- `textfield`、`select`、`toggle` 带本地状态；执行动作时，值作为一个 JSON 参数发送。
- `list`（静态条目）与 `table`（固定列）。
- `prompt:` 动作（通过 manifest 的 `allowPromptActions: true` 显式开启）。

验收标准：

- 一个测试扩展暴露 `/uitest.submit`；按钮能到达它并收到表单 JSON。
- 非法/禁用动作不会崩溃，并会记录一条警告。
- 正在编辑表单时可以关闭窗口。

## M3.4 — 摆放位置 + TUI 对齐 — DONE

已验证：带 `placement: aboveComposer`、`belowComposer` 和 `sheet` 的 pack 窗口
在状态中以正确的摆放位置出现；sheet 通过 `DEBUG_CLOSE_WINDOW` 关闭；`setWidget`
设置/清除了一个面板
（`widgets=[AppWidget(key=demo, lines=…, placement=aboveEditor)]` → `[]`）；
`setTitle` 设置/清除了 `titleOverride`。把输入框 inset 移入下方列之后，发送普通 prompt
仍返回 `OK`。`prompt:` 动作仍按计划未实现。

范围：

- `sheet`、`aboveComposer`、`belowComposer`、`sidebar` 摆放位置。
- 把 `extension_ui_request.setWidget` 的字符串行渲染为 TUI 风格面板
  （输入框上方/下方），让现有扩展获得可见输出。
- `setTitle` → 可选的顶栏标题覆盖。

验收标准：

- 现有的 `setWidget` 调用方无需改动即可显示其文本行。
- widget 与窗口共存；输入框 inset/IME 仍然正确。

## M3.5 — 可选的逃生通道

范围：

- `webview` 节点：仅限本地资源，无网络，窄接口 JS bridge（`action`、
  `value`）。
- 除非 pack 显式开启（`manifest.webview: true`），否则禁用。

验收标准：

- 图表 demo 能渲染；无网络访问；关闭窗口会释放 WebView；JS 无法调用任意的应用方法。

## 注意事项清单

| 风险 | 缓解措施 |
| --- | --- |
| 浮层被键盘遮挡 | 在浮层上使用 `imePadding`；在窗口中放一个 textfield 进行测试 |
| 有窗口打开时按返回键会退出应用 | `BackHandler` 先关闭最上层窗口 |
| 拖拽/缩放超出屏幕、旋转 | 每次拖拽和配置变更时都钳制到窗口边界 |
| pack 推送重置拖拽/z 序 | UI 状态按窗口 `id` 来 key；绝不从节点树重建状态 |
| 已关闭的窗口在下次推送时重新打开 | 已关闭集合，在进程启动 / 文件删除时清空 |
| 多个文件出现重复 `id` | 确定性的最后一份文件生效 + 警告 |
| `ext:` 与 `/skill:` 解析冲突 | 先验证不含冒号的映射（去掉 `ext:` 前缀，`/uitest.submit`） |
| relay 每文件 512 KiB / 跳过二进制 | 窗口都是小 JSON；记录该限制；在应用侧校验大小 |
| 巨大/过深的树冻结 UI | 深度 32、2000 节点、钳制文本、不支持的渲染为占位符 |
| 非法 JSON 杀死所有窗口 | 逐窗口解析；按 `id` 保留上一个可用窗口 |
| 窗口活得比其会话更久 | 已记录：窗口是全局的；扩展在会话变化时重写 |
| 进程死亡 | 启动时从 pack 重建；已关闭集合有意不持久化 |
| 编辑主题会改变已打开窗口的颜色 | 预期行为；颜色来自 `PigTheme` |
| 切换会话会清空 transcript 但不关闭窗口 | 有意为之；窗口是 UI，不是会话数据 |
| 不支持的节点类型 | 占位符 + 警告，绝不抛异常 |
| 无障碍 | 标题/按钮暴露内容描述；最小触摸目标 48dp |

## 验证循环（每个里程碑）

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

状态必须暴露足够的信息，以便不依赖截图进行断言：`windows`、获得焦点的 id、
`windowError`、最后一次 pack 哈希。

## 工作顺序

1. M3.1 模型 + 解析器 + 浮层 + 调试注入。
2. M3.1 验证 + 提交。
3. M3.2 文件通道 + 关闭/协调 + 验证。
4. M3.3 动作 + 表单（包括测试扩展）。
5. M3.4 摆放位置 + `setWidget` 对齐。
6. 仅当原生组件集证明不足时才做 M3.5。

任何该里程碑不需要的东西都记到 scratchpad，而不要塞进同一个构建。
