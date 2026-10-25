[English](README.md) · **简体中文**

# Pig Android

一个原生 Android 客户端，让 PiG 的 agent 继续跑在 Termux 里。
app 只是薄前端：它从不直接和模型供应商通信，也从不持有
API key。所有 agent 工作都发生在原版 `pig` 二进制里。

```
┌──────────────┐   TCP 127.0.0.1:8791   ┌────────────┐   stdio JSONL   ┌──────────────┐
│  Pig app     │ ─────────────────────► │ pig-relay  │ ──────────────► │ pig --mode rpc│
│  (Compose)   │ ◄───────────────────── │   (Go)     │ ◄────────────── │   (Termux)    │
└──────────────┘   one JSON per line    └────────────┘                 └──────────────┘
                                          │
                                          └── watches ~/.pig/android-ui and pushes UI packs
```

## 状态（1.1）

**1.1.1** 让 relay token 不一致可以自愈：重装（或任何 app 数据恢复）之后，
app 过去会继续和仍持有旧密钥的 relay 通信，显示 `auth failed: bad token`
以及 Termux 配置卡片。现在它会从 prefs 重新读取 token，如果读到的正是它
已经发出去的那个，就用正确的 token 在其端口上重启 relay。
**1.1.0** 加入图片：来自工具和附件的对话记录图片块、
输入框里的相册选择、Markdown 图片，以及支持保存到相册的全屏查看器。
**1.0.0** 是首个 minified 发布版（启动图标、
可自选提示音的会话通知、思考块、计划 widget、
用量统计、性能优化）。见[发布构建](#发布构建)。

- App 通过 Termux `RUN_COMMAND` 启动 `pig-relay`（无配对界面）。
- 流式显示助手文本，渲染 Markdown（GFM 标题、列表、代码围栏、
  带对齐和行内格式的表格、链接），渲染带收起/摘要/展开模式的工具卡片
  （`write` 显示行数/字节数，`edit` 显示新增/删除计数和彩色 diff），
  应答 `confirm` / `select` / `input` / `editor` 扩展对话框，并显示状态更新。
  扩展对话框使用 pack 的主题；其正文可选中并带复制按钮（权限提示
  附带命令行帮助）。点击聊天区会隐藏对话框但不作答 —— 请求保持
  pending，输入框上方的横幅可以把它重新调出。
- relay 用每安装实例的 token 认证，原样转发 pig 的 RPC 帧，
  列出项目/会话，并推送可热重载的 UI pack。
- 会话抽屉列出最近的会话（`~/.pig/agent/sessions`），通过
  `switch_session` 切换并用 `get_messages` 重载对话记录（文本 + 工具
  卡片）。切换到最近一分钟内被修改过的文件时会先请求确认，
  因为可能有另一个 pig 进程正在写入它。搜索框按名称、
  预览、id 或 cwd 过滤；每行带一个菜单，可重命名（当前会话走
  RPC `set_session_name`，其他情况则由 relay 追加一条 `session_info` 条目）以及
  删除会话文件。当前活动会话不能删除。
- `nav.json` 条目：扩展可以添加按钮（例如 模型供应商），点击后恢复
  它的窗口并运行 slash 命令刷新它。`placement` 是 `drawer`
  （默认）或 `settings`；后者出现在设置对话框中，因此移动
  扩展条目只是数据修改（无需重新构建 app）。
- 消息带复制按钮（助手头部 / 用户气泡）。向上滚动时对话记录
  停止自动滚动，并显示 回到底部 胶囊按钮；长按
  工具卡片的 收起/摘要/展开 胶囊按钮可将该模式应用到所有工具卡片。
- 模型选择器：点击顶部栏里的模型名可搜索可用
  模型（上下文大小、思考、图片支持），并用 `set_model` 切换。
  星标会为新会话固定一个**默认模型**；每个模型会记住你上次为它
  选择的思考档位。每行的 `ctx` 值都可编辑，因此
  错误的供应商 `contextWindow` 可以手工钉住。
- 点击 新会话 会立即打开空对话并显示 正在新建对话 横幅；
  pig 在后台构建会话，在此期间你输入的内容会排队在 app 里，
  等它报告新会话后立即发送。不预热备用
  进程（内存优先）：新建会话时会复用空闲对话，
  尚无消息的会话则被停放而不是停止。
- 尚无消息的会话不在磁盘上，因此 relay 从
  活动进程列出它（徽标 未发送），并在你切换离开时停放该进程 ——
  切回来会恢复同一个对话。输入框为每个会话保留草稿（也持久化），
  所以输入、切走再切回不会丢文本，新会话仍在创建期间
  输入的文字会在 pig 报告文件后迁移到该文件上。
- 设置是一个可调整大小的对话框，尺寸来自 UI pack
  （`layout/chat.json` → `settings`，预设 + `defaultSize`）；pack 也可以钉住
  一个固定尺寸。它还有自定义的自动压缩阈值（预设
  50–80%）以及一个关于区块，包含 app 版本和 GitHub release 更新
  检查。
- GitHub Actions 在每次 push 时构建 debug APK。

M1 已落地：app 解析推送的 pack 并实时应用 `theme/colors.json` 和
`layout/chat.json`（见 [docs/ui-pack.zh-CN.md](docs/ui-pack.zh-CN.md)）。M2 加入
`app-ui/1` 动作、`ext:` 路由和发布流水线。M3 把 pack
变成任意窗口；overlay/sheet/inline 摆放位置、`windows/` 文件通道
（热重载、新增/更新/移除、关闭）、表单、`ext:` 动作和
`setWidget` 面板均已加入。契约与计划：
[docs/app-ui.zh-CN.md](docs/app-ui.zh-CN.md)、[docs/app-ui-plan.zh-CN.md](docs/app-ui-plan.zh-CN.md)。

## Termux 全新安装

APK 只是前端。从 F-Droid 安装 Termux（Play 版本
已不再维护），打开一次，然后运行：

```bash
pkg install -y curl
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

脚本用官方安装器安装 `pig`，从本仓库的最新 release 下载
`pig-relay-android-arm64`，并创建
`~/.pig/android-ui`。

然后：

脚本启用 `allow-external-apps`（带备份），通过官方安装器安装 `pig`，
下载 `pig-relay`，并创建 `~/.pig/android-ui`。
打开一次 Termux app 让该属性重新加载，然后安装/打开 Pig app
并授予 `com.termux.permission.RUN_COMMAND`。

如果 app 显示初始化对话框，同样的两行命令会带复制
按钮展示出来。一键安装 只在 `allow-external-apps` 已启用时有效；
如果它没反应，就手动把这两行粘贴到 Termux。

手工安装路线与故障排查见 [docs/install.zh-CN.md](docs/install.zh-CN.md)。

正在运行的 relay 在文件被替换后仍持着旧代码。重新运行
脚本（或把新的 `pig-relay` 复制到 `$PREFIX/bin`），然后使用
**设置 → 连接 → 重启 relay**；app 会重启该进程并重连。

## 更新 app

**设置 → 关于 → 检查更新** 会把已安装版本与最新的 GitHub
release 比较。APK 的更新方式是安装新的 `app-release.apk` 覆盖旧版本
（同一签名密钥）。

## 构建 relay

```bash
cd relay
go build -trimpath -ldflags="-s -w" -o "$PREFIX/bin/pig-relay" .
```

## 发布构建

```bash
./gradlew :app:assembleRelease -PversionCode=100 -PversionName=1.0.0
```

R8 + resource shrinking 已开启，因此 APK 约 1.5 MB 而不是约 17 MB（debug），
在这台设备上的启动时间约 240 ms 而不是约 880 ms。CI 在每次
push 时构建它，并把 `pig-release-apk` 与 debug 产物一起上传。

签名：`PIG_RELEASE_STORE_FILE` / `PIG_RELEASE_STORE_PASSWORD` /
`PIG_RELEASE_KEY_ALIAS` / `PIG_RELEASE_KEY_PASSWORD` 会切换到 store key。
不设置它们时构建使用 CI key，与 debug APK 使用的是同一个 key，
因此 release 构建可以覆盖已安装的 debug 版本（`pm install
-r`）并做性能测试。要发布给别人则需要 store key，
因为签名必须在多次更新之间保持稳定。

## 构建 app

本地构建需要 JDK 和 Android SDK，而这里的 Termux 没有这些。
CI 负责构建 APK：

1. push 到 `main`（或开一个 PR / 手动运行 workflow）。
2. 从 Actions 运行记录下载 `pig-debug-apk` 产物。
3. `adb install -r app-debug.apk`（或在设备上直接打开该文件）。

debug workflow 是 `.github/workflows/android.yml`：JDK 17、Gradle wrapper
8.9、`assembleDebug`、产物上传。debug 构建使用仓库内提交的
`keystore/ci-debug.p12`（别名 `androiddebugkey`，密码 `android`），
这样连续的 CI 构建可以互相覆盖安装。

release 构建使用 `.github/workflows/release.yml`，由 `v*` tag 或
手动触发。keystore 来自 `RELEASE_KEYSTORE_BASE64`、
`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS` 和 `RELEASE_KEY_PASSWORD`
Actions secrets；版本名是去掉 `v` 的 tag，版本号
是提交数。本地备份位于
`~/.pig-android-release/pig-release.p12`（不在仓库里）。打 tag 还会创建
一个附带 APK 的 GitHub Release。release key 与 debug
key 不同，因此要在已安装的 debug 构建上安装 release APK 需要
先卸载一次 debug app。

## 运行

通常你只需打开 app：它用存储的 token 启动 relay 并连接。
调试时，可以用 app 使用的同一个 token 自己启动 relay（该 token 存在 app 的
`pig` SharedPreferences 里）：

```bash
pig-relay --port 8791 --token <token> --cwd ~ --ui-dir ~/.pig/android-ui
```

relay 参数：

| 参数 | 默认值 | 含义 |
| --- | --- | --- |
| `--listen` | `127.0.0.1` | 绑定地址（保持在回环地址上） |
| `--port` | `8791` | TCP 端口 |
| `--token` | 自动生成 | 共享 token |
| `--token-file` | | 从文件中读取 token |
| `--cwd` | `$HOME` | pig 工作目录 |
| `--ui-dir` | `~/.pig/android-ui` | UI pack 目录 |
| `--pig` | `pig` | pig 二进制 |

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
- **历史只在 `get_state` 确定会话之后才请求。** 尚未挂载会话的 relay
  会用空列表回答 `get_messages`；把它当成「会话是空的」会在 relay
  重启后立刻清空可见的对话记录。现在 app 会忽略未确定（或当前）
  会话的空历史，并且如果 `get_state` 报告了 `messageCount`
  而对话记录为空，它会再请求一次历史。
- `relay_exit`（pig 崩溃或被杀）也会触发重连循环。
- **无损的一轮中途重新挂接**：relay 持有 pig，并且不再在 app
  断开时杀掉它。重连会挂接到同一个 `pig --mode rpc`
  进程，重放迄今已流式输出的助手文本（`relay_resume`）、正在运行的
  工具进度（`relay_tool`）和未应答的对话框，然后继续推送实时的
  delta。如果 `get_state` 显示 pig 正在已保存的会话上流式输出，app
  会跳过 `switch_session` 并绑定到那个部分的助手气泡上。

## UI pack

`~/.pig/android-ui/` 驱动颜色、字体排版、布局开关、组件
可见性/尺寸、图标、nav 条目、窗口、资源和**所有 app 自有的
字符串** —— 包括初始化页、对话框、工具指标、通知文本和
启动错误。添加 `skins.json` 可获得命名主题变体以及抽屉 /
设置中的皮肤切换器（`layout/chat.json` → `skinSwitcher`）。抽屉/设置里的
**UI pack 管理** 可以从 ZIP 或文件夹导入 pack 到
app 的私有存储，并在它们与 relay pack 之间切换
（`layout/chat.json` → `packManager`）。键分组
与占位符见 `docs/ui-pack.md`。

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
（见 [docs/ui-pack.zh-CN.md](docs/ui-pack.zh-CN.md)）。`/compact` 之后，PiG 在
下一次模型调用之前会返回 null 上下文
用量，因此头部显示 `已压缩` 而不是有误导性的 `0%`；真实数字在
下一轮之后重新出现。

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
  以及流式时的自动展开。见 [docs/ui-pack.zh-CN.md](docs/ui-pack.zh-CN.md)。
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
扩展 —— 任何发出[计划 widget 契约](docs/plan-widget.zh-CN.md)的扩展
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
[docs/plan-widget.zh-CN.md](docs/plan-widget.zh-CN.md)。

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

## 目录结构

```
app/                     Compose client
  src/main/java/com/linuxwff/pigapp/
    MainActivity.kt      permission request + relay launch
    RelayClient.kt       TCP JSONL client with retry
    RelayLauncher.kt     Termux RUN_COMMAND intent
    AppViewModel.kt      RPC event → UI state
    ui/ChatScreen.kt     sidebar, transcript, tool cards, dialogs
relay/main.go            loopback bridge, auth, UI pack watcher
docs/protocol.md         wire protocol
docs/ui-pack.md          declarative theme/layout schema
docs/app-ui.md           app-ui/1 window schema (M3 contract)
docs/app-ui-plan.md      M3 milestone order, acceptance, pitfalls
examples/ui-pack/        sample declarative UI pack
```

## 安全说明

- relay 只绑定 `127.0.0.1`，并要求在第一帧里带上 token。
- `allow-external-apps=true` 让任何持有 `RUN_COMMAND` 的 app 都能在 Termux 里
  执行命令。这是无配对启动路径的取舍。如果你不
  需要它，可以改从 Termux service 启动 relay。
- API key 留在 Termux（`~/.pig/agent/auth.json`）；app 从来看不到它们。

## 从 shell 驱动 app（debug 构建）

debug 构建注册了 `com.linuxwff.pigapp/.DebugReceiver`，因此你可以在不碰屏幕的情况下
驱动客户端。务必传 `-n`；app 可能处于
后台，而隐式广播不会投递给被缓存的 app。

```bash
# 当前连接 / 模型 / 消息（logcat tag PigDebug）
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_STATE
logcat -d -s PigDebug

# 发送 prompt
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_PROMPT --es text "Reply with exactly OK"

# 切换到某个会话文件
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_SWITCH --es path /path/to/session.jsonl

# 模型 / 思考
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_MODELS
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_SET_MODEL --es provider deepseek --es model deepseek-flash
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_THINKING --es level high

# 重连（需要时会重新拉起 relay）
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_CONNECT

# 中止当前轮次
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ABORT

# 应答待处理的 confirm/select/input 对话框
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ANSWER --es value "Allow"
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ANSWER --ez confirmed true
```

`DEBUG_STATE` 还会写入
`/storage/emulated/0/Android/data/com.linuxwff.pigapp/files/state.txt`（root 可以
读取），从而避免依赖 logcat。

`DEBUG_PROMPT` / `DEBUG_FORM` 作用于**当前活动会话**。发送测试 prompt 前
先把 app 切到一个草稿会话；否则它们会落在当前打开的
那个对话里。

该 receiver 位于 `app/src/debug/`，不属于 release 构建。
