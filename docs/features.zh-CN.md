[English](features.md) · **简体中文**

# 功能详解

导览在 [README](../README.zh-CN.md) 里；本文件是参考：app 的每个部分做什么，
以及它为什么这样表现。

## 重连与会话恢复

app 的设计目标是能在锁屏、Doze 和进程被杀后存活：

- 只要 socket 断开，`MainActivity.onResume` 就会重连（并通过 Termux 重新拉起
  `pig-relay`）。断连期间，只要 app 在前台，`startAutoReconnect` 就会
  带退避一直重试。
- 客户端每 4 s 发送一次 `relay_ping`；如果 12 s 内没有收到 `relay_pong`，
  就认为 socket 已死（进程冻结、relay 被杀）并丢弃它，
  随后由重连循环接管。短暂的 `onPause` 不会拆掉
  存活的连接。
- 活动会话文件存在 app 的 `pig` SharedPreferences
  （`lastSessionFile`）里。relay 总是启动一个全新的 `pig`，因此握手之后
  app 会为该文件发送 `switch_session` 并重载其
  对话记录。如果该会话已无法打开，app 回退到
  一个新会话。
- **只有在 `get_state` 确定会话之后才请求历史。** 尚未挂载会话的 relay
  会用空列表回答 `get_messages`；把它当成「会话是空的」会在 relay
  重启后立刻清空可见的对话记录。现在 app 会忽略未确定（或当前）
  会话的空历史，并且如果 `get_state` 报告了 `messageCount`
  而对话记录为空，它会再请求一次历史。
- `relay_exit`（pig 崩溃或被杀）也会触发重连循环。
- **无损的一轮中途重新挂接**：relay 持有 pig，并且不再在 app
  断开时杀掉它。重连会挂接到同一个 `pig --mode rpc`
  进程，重放迄今已流式输出的助手文本（`relay_resume`）、正在运行的
  工具进度（`relay_tool`）和未应答的对话框，然后继续推送实时的
  增量。如果 `get_state` 显示 pig 正在已保存的会话上流式输出，app
  会跳过 `switch_session` 并绑定到那个部分的助手气泡上。

## 会话与设置

- 抽屉列出最近的会话（`~/.pig/agent/sessions`），通过 `switch_session` 切换，
  并用 `get_messages` 重新载入对话记录（文本和工具卡片）。切换到最近一分钟内
  被改动过的文件时会先请求确认，因为可能有另一个 pig 进程正在写它。搜索框按
  名称、预览、id 或 cwd 过滤；每行的菜单可以重命名（对活动会话走 RPC
  `set_session_name`，否则追加一条 relay 的 `session_info` 记录）或删除会话文件。
  活动会话不能删除。
- 点 `新会话` 会立刻打开一个空对话和一条 `正在新建对话` 横幅；pig 在后台创建
  会话，这期间输入的文字会排队保存在 App 里，一收到新会话就发出去。不预热任何
  备用进程（内存优先）：新建会话时会复用空闲对话，而还没有消息的会话会被挂起
  而不是停掉。
- 还没有消息的会话不在磁盘上，因此 relay 从活动进程里列出它（徽标 `未发送`），
  并在你切走时挂起该进程 —— 切回来还是同一个对话。输入框按会话保存草稿（也会
  持久化），所以输入、切走、再回来都不会丢字；新会话还在创建时输入的文字，会在
  pig 报出新会话后落到该会话的文件里。
- `nav.json` 条目：扩展可以加一个按钮（例如 `模型供应商`），点击后恢复它的窗口
  并执行一条斜杠命令刷新内容。`placement` 可以是 `drawer`（默认）或 `settings`；
  后者出现在设置对话框里，所以挪动扩展入口只是改数据（不用重新构建 App）。
- 消息带复制按钮（助手头部 / 用户气泡）。向上滚动时对话记录会停止自动滚动，
  并显示 `回到底部` 胶囊按钮；长按工具卡片的 `收起/摘要/展开` 胶囊按钮，可把该
  模式应用到所有工具卡片。
- 模型选择器里的星标会为新会话钉住**默认模型**，并且每个模型都会记住你上次为
  它选的思考档位。
- 设置是一个可调整大小的对话框，尺寸来自 UI pack
  （`layout/chat.json` → `settings`，预设 + `defaultSize`）；pack 也可以钉死一个
  固定尺寸。它还有一个自定义自动压缩阈值（预设 50–80%），以及显示 App 版本并
  检查 GitHub 发布更新的「关于」区。

## 备份、恢复与卸载

设置 → **备份与维护** 可以把整个 Termux 端安装搬走，也可以把它删掉。App 从不
直接读写 `~/.pig`：relay 用同一条回环连接把数据流式传出来（协议见
`docs/protocol.md`）。

- **导出备份** 在 Android 10+ 把 gzip tar 写到
  `Download/Pig/pig-backup-<时间>.tar.gz`（MediaStore，不需要权限）；更老的系统
  走系统保存对话框。两个开关决定内容：**会话记录**（体积大头，几十 MB）和
  **UI pack**；API key、`models.json`、`settings.json`、
  `custom-endpoints.json`、扩展和 `~/.pig/state` 始终包含。因此文件里有
  **明文密钥** —— 第一次导出前对话框会明确提示。二进制、`~/.pig/cache`（编译好的
  Go 扩展 runner）、崩溃输出、日志和符号链接不打包。relay 自己写的、启动时会重建的
  副本也不打包（带 `.embedded-hash` 的目录，如 `agent/pigkit/<pkg>` 和
  `agent/extensions/plan`）；没有 marker 的用户包会保留。
- **导入备份** 选择一个 `.tar.gz` 并确认一次。relay 先校验 checksum，把当前
  数据完整备份到 `~/.pig/backups/`，再把归档合并进 `~/.pig`（同名文件被覆盖，
  归档里没有的会话保留）。只接受 `.pig/` 下的条目，路径穿越、符号链接和设备
  文件会被忽略。恢复后的 `settings.json` 会做清理：指向 relay 自带扩展（例如 `plan`）
  另一份副本（开发机 checkout）的 package 条目会被移除，优先保留内置副本，避免
  备份让 pig 陷入退出/重试循环。完成后 relay 会停掉所有 pig 进程，确保恢复的设置、模型和密钥
  真正生效；App 自动重连并重新加载 UI pack 与会话列表。Go 编译缓存不在备份里，
  所以恢复后第一次启动会重新编译内置扩展（手机上几秒到一分钟），和全新安装一样。
- **卸载 pig 与 pig-relay** 会停止两个进程，并删除 `$PREFIX/bin/pig`、
  `pig-relay` 和旧的 `pig-relay.*` 副本以及安装回执。可选的
  **同时删除 ~/.pig 数据**（默认关闭）会连配置、密钥、模型、会话和 UI pack
  一起删掉。勾选时 App 会先把 `~/.pig` 打包到
  `~/pig-before-uninstall-<时间>.tar.gz`（排除 `cache/`、`docs/`），成功后才删除；
  备份失败则中止卸载并保留数据。不勾选时，之后重跑安装命令即可原样恢复。
  注意安装脚本本身从不删除 `~/.pig`，只有这个开关会。

备份和恢复都需要 relay **1.1.34+**：握手会声明
`features: ["backup","restore"]`，连接旧 relay 时按钮保持禁用并给出提示。
进度（已传字节，导出时还有百分比）显示在同一区域；关掉对话框不会中断传输。

## relay 版本检查

`relay_hello` 会带 relay 的 `version`（1.1.34+）。正式构建会把它和 App 的 versionName
比较，并在 **设置 → 连接** 显示结果：

- relay 较旧（或 1.1.27 之前没有版本号）→ 红色一行 + **更新 relay**：重跑安装脚本
  （按 App 的 tag 安装，先编译扩展），Termux 返回后自动重启 relay；没有
  `RUN_COMMAND` 权限时改为复制命令并打开 Termux。连接时会弹一次提示指向这一行。
- relay 较新 → 提示建议更新 App，**不会**降级 relay。
- 相同 → 不提示。debug/CI 构建（`0.1.0`/dev 版本）跳过比较，开发时不会被烦。

## UI pack

`~/.pig/android-ui/` 驱动颜色、字体排版、布局开关、组件
可见性/尺寸、图标、导航条目、窗口、资源和**所有 app 自有的
字符串** —— 包括初始化页、对话框、工具指标、通知文本和
启动错误。添加 `skins.json` 可获得命名主题变体以及抽屉 /
设置中的皮肤切换器（`layout/chat.json` → `skinSwitcher`）。抽屉/设置里的
**UI pack 管理** 可以从 ZIP 或文件夹导入 pack 到
app 的私有存储，并在它们与 relay pack 之间切换
（`layout/chat.json` → `packManager`）。键分组
与占位符见 `ui-pack.md`。

## 模型选择器

当模型声明支持推理时，选择器会显示 `思考` 徽标
（徽标只是信息性的 —— 有些供应商会省略该标记，所以
没有推理过滤）。思考档位因模型而异；非推理模型
只报告 `off`。切换之后选择器保持打开，显示 `切换中…`，然后
刷新可用的思考档位，以便立即修改。

`/v1/models` 代理很少暴露能力元数据，所以自动发现的供应商下
每个模型到来时都是 `reasoning: false`，只报告
`off`。在 `~/.pig/agent/models.json` 的
`providers.<id>.modelOverrides.<model>` 下添加 `reasoning: true` 以及 `thinkingLevelMap`
即可让它启用（`~/bin/pig-eu-reasoning-sync` 会对 `eu` 供应商做这件事）。
只映射上游接受的档位 —— 把某个档位映射到 `null` 会**移除**它，包括
`off`。改动在 pig 进程启动时生效。

点击任意行上的 `ctx` 一行即可钉住上下文窗口。供应商
经常报告错误或缺失的 `contextWindow`；该覆盖接受纯 token 数或
`128K` / `1M`，按 `provider/model` 存储，并立即
重算头部百分比（`used / override`）。`恢复自动` 会移除它并回退到
供应商报告的值。

## 供应商管理

设置 → **模型供应商**（UI pack 的 `nav.json` 条目，`window: native:providers`）打开的是
App 原生页面，不再是 pack 生成的窗口：

- 列出 `~/.pig/agent/custom-endpoints.json` 里的每个供应商：Base URL、API 类型、
  模型数量、是否保存了 Key、是否自动获取。`models.json` 里定义的供应商只读展示。
- **新建 / 编辑** 写的就是 `custom-endpoints` 扩展读取的那个文件，并保留表单不编辑
  的键（`headers`、`oauth`、`defaults`、刷新选项）。API Key 只写不读：App 只知道
  `hasKey`，所以留空表示保持原 Key，勾选可以清除。
- **刷新模型列表** 让 relay 请求 `{baseUrl}/models`（或 `modelsUrl`），鉴权规则与会话
  一致（`Authorization: Bearer`、Anthropic 用 `x-api-key`、Google 用 `?key=`、
  自定义 `headers`/`modelsHeaders`），随后弹出 **原生选择窗**：可搜索、可勾选、
  支持全选/清空，显示 id 与上下文大小。勾选的子集会写成显式 `models` 数组，完整
  列表仍留在 `custom-endpoints.cache.json`，再次打开选择窗不需要联网。如果草稿刷新失败
  （超时、5xx，或点刷新后才填 key），auto 模式保存时会再抓一次并写缓存，会话重启后
  模型立即就绪；保存时的抓取失败也会保存，并提示 pig 启动时会重试。
- **自动获取全部模型** 保持 `models: "auto"`：下次会话启动时注册全部模型；端点
  挂掉时使用缓存列表。
- 保存/删除后会重启 pig（同一会话文件），让扩展重新读文件；随后 App 刷新
  `get_available_models`。`$ENV` 与 `!命令` 会在 Termux 里展开，`PIG_OFFLINE=1`
  时禁止获取。

`provider-ui` 扩展仍保留给 CLI 用户（`/provider-ui`、`/providerui.save`、
`/providerui.refresh`、`/providerui.remove`），但不再写 `windows/providers.json`。

## 首次启动的供应商配置

全新的 `~/.pig` 没有任何 provider，`get_available_models` 返回空列表。App 把它当作
配置向导而不是错误：输入框上方会出现一张卡片，提供 **添加供应商**（原生供应商页）
和 **备份 / 设置**（导出/导入备份）；模型选择器空列表时也有同样按钮；没有模型时发送
消息只会提示，不再打红色 pig 错误。设置 → 连接 里还有固定的 **模型供应商** 一行，
不依赖 UI pack 的 `nav.json`；relay 在还没启动 pig 时也会回答空的模型列表，所以全新
后端也能正常显示首次配置卡片。关掉卡片后本次进程不再打扰，模型再次消失时会重新
出现。内置 provider 在 Termux 里用 `pig login` / `pig auth` 配置；自定义端点用供应商页。

## 图片

图片以
`{"type":"image","data":"<base64>","mimeType":"image/png"}` 块的形式内联在 pig 的消息内容里，
relay 原样转发它们。app 在收发两个方向上都会渲染：

- **Agent → app.** 工具输出会保留其图片：对图片执行 `read`、
  截图工具、MCP 服务器和图片模型都会在文本旁返回 `image` 块。
  它显示在工具卡片内部（所有模式，包括
  摘要），助手消息也可以携带图片块。点击任意
  图片 —— 对话记录块或 Markdown `![…](…)` —— 可打开全屏查看器
  （双指缩放、拖动平移、双击复位）。查看器的保存按钮
  通过 MediaStore 把它写入 `Pictures/Pig`（Android 10+ 无需存储权限；
  更早的版本使用 app 的外部 Pictures 目录）。
- **App → agent.** 输入框的图片按钮会打开系统相册选择器
  （无需存储权限）。选中的图片会被缩放到最长边 1568 px
  并重新压缩（JPEG，源图带 alpha 时用 PNG），之后才
  成为 RPC `images`，因此 prompt 和会话文件保持较小。它们会在下一次
  `prompt` / `steer` / `followUp` 时发送。图片允许不带文本，
  当前模型未声明图片输入时 app 会给出警告。
- **Markdown。** 助手 Markdown 中的 `![alt](https://…)`、`data:image/…;base64,…` 和 app 可读的
  `file:` 链接会在后台抓取并由渲染器绘制（12 条内存缓存，
  8 MiB / 1600 px 上限）。

历史重建在主线程之外解码图片（最长边 1024 px，每个会话约 24 MB
像素预算），因此含截图的 2000 条消息的对话记录仍可
流畅浏览。超大的块 —— 图片字节超过约 6 MiB、单条消息超过八张，
或超出预算 —— 会被跳过并显示为 `N 张图片未显示`。
切换会话时会随对话记录一起丢弃已解码的 bitmap。

## 上下文与缓存

头部显示当前上下文百分比/token 数以及累计
prompt 缓存命中率（`缓存 78%`）。**设置 → 数据 → 使用统计** 打开
来自 `get_session_stats` 的完整会话计数 —— 输入/输出/缓存 token、缓存
命中占比、成本、消息/工具计数 —— 覆盖整个会话文件，而不只是
已加载的历史。pack 可以通过 `stats.*` 绑定同样的数字
（见 [ui-pack.zh-CN.md](ui-pack.zh-CN.md)）。`/compact` 之后，PiG 在
下一次模型调用之前会返回 null 上下文
用量，因此头部显示 `已压缩` 而不是有误导性的 `0%`；真实数字在
下一轮之后重新出现。

## 插队与排队

回合进行中时，发送键有两个动作：

- **点按** —— `steer`，在当前工具批次之后注入正在运行的回合（插队）；
- **长按** —— `follow_up`，等回合结束后再发送（排队）。

PiG 用 `queue_update` 同时上报两个队列，所以输入框上方的提示会写清真正在等的东西
（`已插队 1 · 已排队 2`），不会再把排队的消息也叫成「已插队」。点提示条打开
「待发送消息」：

- 点一行会把这条消息取回输入框（同时移出队列），所以排队/插队中的消息在真正执行前
  还能改；已经被 pig 消费的消息属于会话记录，改不了；
- 每行显示类型和内容，点删除图标把这一条移出队列；
- **全部清空** 丢掉两个队列（`clear_queue`）；
- pig 没有「只删一条」的 RPC，所以删除一行时会先清空两个队列，再按原顺序用
  `steer` / `follow_up` 把剩下的重新排进去；这些消息不会重复出现在对话里；
- 重连后 `get_state` 只有 `pendingMessageCount`，因此对话框先显示数量（和清空按钮），
  直到下一次队列变化再补上内容。

设置 → 会话行为 → 打断消息 / 追问消息 仍然分别选择 `one-at-a-time` 或 `all`。

## 思考

推理块（`content: [{"type":"thinking", …}]`）是对话记录的一部分，
不是通知：

- 它们会在模型思考时实时流入（`thinking_delta` / `thinking_end`），并在
  重新加载时从历史重放。
- 每个块渲染在答案**上方**，是一个收起的 `思考 · N 字` 区块；
  流式输出时自动展开，答案开始时收起。点击
  头部可逐条消息切换。
- 只做推理然后调用工具的步骤不会有自己的气泡：
  那部分推理被折进工具卡片分组（同样收起），因此一长串
  同名调用保持为一个分组，而不是被思考气泡
  拆开。
- `settings.json` 中的 `hideThinkingBlock` 可隐藏它们；**设置 → 会话行为 → 显示思考过程**
  通过 `relay_settings` 翻转它并立即生效。
- 该块可由 pack 配置（`layout/chat.json` 里的 `chat.thinking`）：标题、
  字号/斜体、颜色、圆角、内边距、最大高度、默认收起
  以及流式时的自动展开。见 [ui-pack.zh-CN.md](ui-pack.zh-CN.md)。
- 会隐去推理的供应商会发送带 `redacted: true` 的空块；
  app 显示 `（提供方已隐藏思考内容）` 而不是空白区块。
- `thinking_level_changed` 会更新头部，因此由 pig 或
  扩展修改的档位不会留下过期值。

## Slash 命令

输入框识别 `/name args`：

- `/compact`（别名 `/compress`）发送 RPC `compact` 命令。
- `/new`、`/name <name>`、`/thinking <level>`、`/model`、`/stop` 映射到 app/RPC 动作。
- 注册的扩展命令由 RPC `get_commands` 调用列出，并作为
  `prompt` 发送；PiG 会在不调用模型的情况下运行它们。
- 输入 `/` 会打开命令面板，含内置命令和扩展命令；
  点击某一行会填入 `/name `。未知命令会被提示条拒绝
  而不是发给模型。
- 命令列表在连接时、进入 `/` 模式时、每轮之后以及运行扩展命令 1.5 s 之后
  刷新。在 Termux 里安装扩展仍需要运行中的 pig 加载它：运行
  `/reload-runtime`（或重启 agent），然后重新打开面板。

### 计划模式（可选）

本仓库中的 `extensions/plan/` 是一个可选的 Go 扩展：`/plan`
切换到只读的计划模式，模型用 `Plan:` 列表作答，
`/plan go`（或对话框）开始执行它，同时 `[DONE:n]` 标记会逐项勾掉
步骤。安装方式：

```bash
pig install ./extensions/plan        # after cloning this repo
```

然后重启 pig（**设置 → 会话行为 → 重启 pig**）。`/reload-runtime` 只会重建
启动时它已知道的扩展；之后添加到
`settings.json` 的包需要重启。app 并不依赖这个
扩展 —— 任何发出[计划 widget 契约](plan-widget.zh-CN.md)的扩展
都会得到同样的卡片。

`compact` 也可以从 **设置 → 数据 → 压缩上下文** 触发。

## 会话行为与调优

**设置 → 会话行为** 驱动 PiG 通过 RPC 暴露的运行时开关：

- **自动压缩上下文** → `set_auto_compaction`（`compaction.enabled`，由
  PiG 持久化）。当上下文超过 `contextWindow − reserveTokens` 时触发；
  关掉它之后 `/compact` 就是唯一的压缩方式。
- **自动重试** → `set_auto_retry`（会话级，`get_state` 不会回传，
  因此 app 在本地跟踪它，并在切换会话时重置）。
- **打断消息 / 追问消息** → `set_steering_mode` / `set_follow_up_mode`
  （`one-at-a-time` 或 `all`）。

模型选择器里选的上下文长度不再是仅用于显示：app 会发送
`relay_model_override`，它写入
`~/.pig/agent/models.json` 里的
`providers.<provider>.modelOverrides.<model>.contextWindow`（原子写入，`.bak-*-pre-app` 备份）。
Pig 只在进程启动时读一次该文件，不会在 `set_model` 时重读，所以
app 随后发送 `relay_restart`：relay 停掉 agent 并带上其历史重新打开
同一个会话文件，钉住的窗口会在一两秒内生效（状态 `恢复中`）。
如果正在跑一轮，重启会等待 `agent_end`。钉住的值仍在等待生效时，
头部会在 ctx 一行后追加 `⏳`（例如 `ctx 57% / 1M ⏳`）；pig 报告新窗口后
它立刻消失。清除钉住会删掉该键并以同样方式重启 pig。

扩展状态行（`ctx.ui.setStatus(key, text)`）保留其 key：app
把它们显示在自己的活动标签旁边（`处理中 · ⚡ 38.2 tok/s`），空的
`statusText` 只清除该 key。扩展状态行会活过写它的那一轮，
因此它们以普通标签展示 —— spinner 属于 app 自己的
活动（思考/工作/压缩）并随之停止。Widget
（`ctx.ui.setWidget`）像以前一样渲染在输入框上方或下方。key 为 `plan` / `plan-todos` / `todos`
且行内容符合计划契约的 widget 会变成清单卡片（标题、进度条、`☑/☐` 步骤）——
任何扩展都可以加入，因此用户可以选我们的可选计划扩展或
第三方扩展。卡片会收起到单行（按 key 记忆），
并且可以关闭直到内容变化。见
[plan-widget.zh-CN.md](plan-widget.zh-CN.md)。

失败会成为对话记录的一部分而不是提示条：pig 自己失败的轮次
会存进会话文件（`errorMessage`）并在加载时重放，而 app 看到的
relay/传输失败则按会话保存在
`files/notices/<session>.jsonl` 并合并进历史。两者都渲染为
消息之间的通知行，因此往回滚动或重启 app 后
仍能看到出了什么问题。

**设置 → 压缩与重试** 还提供 **自动压缩阈值**，以窗口的比例表示
（60/70/80/88/90 %）。PiG 只知道绝对余量，所以 app 会用
`reserveTokens = window × (1 − threshold)` 换算选中的值，并通过
`relay_settings` 写入。使用的窗口是 pig 报告的那个（也就是
压缩比较时用的那个），因此尚未生效的钉住值不会
产生大于真实窗口的预留量；提示里说明了这一点并显示
换算后的数字。下面那条绝对值 `压缩预留 tokens` 仍可用
于精确数值。

该区块编辑的值只存在于
`~/.pig/agent/settings.json`（`compaction.reserveTokens`、
`compaction.keepRecentTokens`、`retry.maxRetries`、`cacheWarming`）。app 对这些
没有 RPC，所以它请求 relay —— `relay_settings` 读取文件并
合并一份白名单补丁，同时保留 `settings.json.bak-*-pre-app` 备份。
旧 relay 会回 `relay_settings_error`；此时该区块显示提示
而不是假装保存成功。Pig 在进程启动时读取这些值，因此它们对
新启动的会话生效。

## WebDAV 备份

设置 → 备份与维护 → **WebDAV 备份**，把和「导出备份」同一份归档（会话、设置、密钥、
扩展、UI pack）上传到任何支持 Basic 认证的 WebDAV：Nextcloud、Alist、群晖、
`rclone serve webdav` 都行。

- **服务器地址 / 用户名 / 密码 / 远程目录**：目录不存在时首次上传会自动 `MKCOL` 创建；
  地址可以直接指向用户目录，例如 `https://dav.example.com/remote.php/dav/files/me`。
- **保留 N 份**：每次上传后删除该目录里超出 N 份的旧 `pig-backup-*.tar.gz`。
- **定时备份**：关闭 / 每 6 小时 / 每 12 小时 / 每天 / 每 3 天 / 每周。用 WorkManager
  定时（要求联网），重启手机或 App 都不会丢；打开后第一次执行在一个周期之后。
  **每天**还可以选一个时间点（默认 03:00）：第一次执行等到那个整点，之后每 24 小时一次。
  所有档位都是周期任务，某一次失败或者没被拉起执行，下一轮照样会来。App 进程
  还活着时后台任务走 App 自己的 relay 连接（另开一条会把 App 顶掉、App 又顶回来，relay
  会一直闪断）；只有在没有 App 进程时才自己开一次性连接（Termux 没跑 relay 会先拉起）。
  保存过服务器后，设置里还会多出「立即同步」按钮，点一下就用已保存的配置上传一次，
  结果用底部提示显示。
- **测试连接**做一次 `PROPFIND`（目录缺失时 `MKCOL`）；**立即备份**上传一次。
- **存档管理**列出服务器上的所有归档（本地时间 + 大小），可以刷新、立即备份，
  每条还能「恢复」或「删除」（删除会先确认）。恢复走的是原来的恢复流程，relay 会先
  备份现有的 `~/.pig` 再解压。
- 恢复会先把归档下载到 App 缓存，再走原来的恢复流程：relay 先备份现有 `~/.pig`，
  再解压，最后 App 重连。

- **http / 自签名证书**：App 允许明文 HTTP（地址以 `http://` 开头时对话框会红字提示），
  **忽略证书错误** 只对该服务器跳过证书与域名校验。两者都只是为了兼容自建 NAS，
  推荐仍然是受信任证书的 HTTPS。

安全：密码存在 App 私有设置里（未加密），归档本身含明文 API key —— 请用 HTTPS，
并限制该目录的访问权限。

## 通知

app 在后台时，会话状态变化会发出通知。
两个通道让这一点可以从系统设置里控制：

| 通道 | 重要性 | 用于 |
| --- | --- | --- |
| 会话进展（`pig_updates`） | 默认 | 一轮结束、扩展 info 消息、压缩（静音） |
| 需要处理（`pig_alerts`） | 高 | 扩展正在等待 confirm/select/input/editor、请求或连接错误 |

哪些状态会通知：

- **回复完成** —— `agent_settled` 且 `aborted: false`（被中止的轮次保持
  安静），文本是最后一条答案的第一行。
- **需要你的操作** —— 扩展打开了对话框并正在等待。
- **出错了** —— pig `error` 事件或 relay 错误（协议兼容性噪音
  如 `unknown relay frame` 会被排除）。
- **上下文已压缩** —— `compaction_end`，静音且默认关闭。
- 扩展的 `notify` 调用按级别映射：`info` → 会话进展，`warning` →
  需要处理，`error` → 需要处理。

通知提示音遵循 Android 的通道模型，因此 app 在
**设置 → 会话行为 → 通知提示音** 中提供实用的选项：系统默认 / 静音 / 闹钟 /
铃声。选择某一项会创建对应声音的通道并删除之前的
变体（Android 把声音绑定在通道上）。**打开系统通知设置…** 会打开
该通道页面，可设置自定义声音、振动和重要性 —— 用户在系统中
自定义过的通道会保持自定义，直到 in-app 的选项发生变化。

这四项都是 **设置 → 会话行为** 中的开关（完成 / 需要操作 / 出错 / 压缩）。
完成 开关也覆盖普通的扩展 `info` 消息，因为两者落在
同一个通道上。
点击通知会打开 app；回到前台会取消 app 发出的
通知。Android 13+ 在首次启动时请求通知权限。

## 保活

- `KeepAliveService` 是由 `MainActivity` 启动的 `specialUse` 前台服务。
  它在熄屏时保持进程（以及回环 socket）存活；Android 14+/15 不再
  激进地缓存 app。常驻通知被点击时会打开 app。
- 和 `termux-wake-lock` 一样，该服务默认持有一个 `PARTIAL_WAKE_LOCK`
  （`Pig::RelayConnection`），这样长时间运行的轮次中 CPU 不会被挂起。
  它和前台服务都由 **设置**（抽屉）控制：`keepalive` 启停该服务，
  `wakelock` 控制 CPU 锁；两个选择都存进 `pig` SharedPreferences。
- `MainActivity` 是 `singleTask`：重复启动（图标、通知、
  `am start`）会复用已有实例，而不是创建第二个
  ViewModel/RelayClient 和重复的 relay 连接。
- 首次启动 relay 时，app 会为每个进程通过 `RUN_COMMAND` 运行 `termux-wake-lock`，
  这样 Termux 在长时间轮次中不会被冻结。
- **设置** 里还有电池一行，它会为 app 请求
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`。如果该对话框被拦截，
  就在 系统设置 → 电池 里手动允许该 app。在激进的厂商 ROM（MIUI 等）上
  也建议允许 Termux。
- Android 13+ 会请求 `POST_NOTIFICATIONS`；被拒绝时服务仍会运行，
  只是通知被隐藏。
