[English](protocol.md) · **简体中文**

# relay 协议（`relay/1`）

回环 TCP，每行一个 JSON 对象（LF）。分帧方式与 `pig --mode rpc` 相同。pig 自身的帧按字节原样转发；relay 控制帧是 `type` 以 `relay_` 开头的那些帧。

## 握手

客户端必须在 5 秒内发送：

```json
{"type":"relay_auth","token":"<shared token>"}
```

成功后 relay 启动 `pig --mode rpc` 并发送：

```json
{"type":"relay_hello","protocol":"relay/1","version":"1.1.34","features":["backup","restore","providers","install"],"cwd":"/data/data/com.termux/files/home","uiDir":"/data/data/com.termux/files/home/.pig/android-ui","pigPid":12345}
```

`features` 列出 relay 支持的可选控制帧。客户端应先看它，而不是靠
`unknown relay frame` 才发现帧不存在；1.1.34 之前的 relay 不带这两个字段。
`version` 是去掉 `v` 的 release tag（手工构建为 `dev-<sha>`）；正式客户端应把它和
自己的版本比较，relay 较旧时提示重跑安装脚本。relay 较新是合法的——feature 是增量的，
安装脚本也故意只升不降。`installing` 为 true 表示 Termux 安装脚本正在运行
（见「安装进度」）。

失败时它发送 `relay_error` 并关闭连接。token 使用常量时间比较。

## 客户端 → relay

任何 `type` **不**以 `relay_` 开头的帧都会原样写入 pig 的 stdin。例如：`prompt`、`abort`、`get_state`、`new_session`、`extension_ui_response`。

控制帧：

| 帧 | 字段 | 响应 |
| --- | --- | --- |
| `relay_ping` | `id` | `relay_pong` |
| `relay_projects` | `id`，可选 `root` | `relay_projects`，带 `{name, path}` 列表 |
| `relay_session_rename` | `id`、`path`、`name` | `relay_session_renamed`（`path`、`name`） |
| `relay_session_delete` | `id`、`path` | `relay_session_deleted`（`path`） |
| `relay_ui` | | `relay_ui_pack` 快照 |
| `relay_settings` | `id`，可选 `set` 对象 | `relay_settings`（`settings`、`editable`） |
| `relay_settings` | `set` 只合并允许列表中的键 | `relay_settings_error`（`message`） |
| `relay_model_override` | `id`、`provider`、`model`、`contextWindow` | `relay_model_override`（`provider`、`model`、`contextWindow`） |
| `relay_model_override` | `contextWindow` 超出 0…10 000 000 或 provider 未知 | `relay_model_override_error`（`message`） |
| `relay_restart` | | 杀死并按同一 session 文件重新启动 pig（`relay_restarted`）；用于固定上下文窗口后 |
| `relay_backup` | `id`，可选 `sessions`/`ui` 布尔值（默认 true） | `relay_backup_start`，然后若干 `relay_backup_chunk`，最后 `relay_backup_done` 或 `relay_backup_error` |
| `relay_backup_cancel` | `id` | 正在运行的导出以 `relay_backup_error`（`cancelled: true`）结束 |
| `relay_restore_begin` | `id`、`name`、`size` | `relay_restore_ready`（`max` 为归档大小上限）或 `relay_restore_error` |
| `relay_restore_chunk` | `id`、`data`（base64） | 无；出错时以 `relay_restore_error` 中止 |
| `relay_restore_end` | `id`、已上传字节的 `sha256` | `relay_restore_done`（`entries`、`bytes`、`backup`）或 `relay_restore_error` |
| `relay_stop` | | 关闭连接 |

## 安装进度

Termux 安装脚本运行时会把进度写到 `~/.pig/install-status.json`：

```json
{"state":"running","step":6,"total":9,"message":"安装 pig-relay","updated":1793086502}
```

`state` 是 `running`、`done` 或 `error`，`updated` 是 unix 秒。带 `install` feature
的 relay 会监视该文件：`state=running` 时**不启动 pig**（agent 帧被丢弃，ping、UI
和 relay 控制帧仍然工作），每次变化都转给 App，并在 `relay_hello` 之后立即发送
当前阶段：

```json
{"type":"relay_install","state":"running","step":6,"total":9,"message":"安装 pig-relay","updated":1793086502}
```

`done`（或 `error`）后闸门打开，App 应重新发送 `get_state` / `get_messages` /
`get_available_models`，因为安装期间发出的请求已被丢弃。超过 45 分钟的状态会被
忽略，避免脚本崩溃后永远锁住 agent；文件不存在表示没有安装在进行。

## 历史记录分页

`get_messages` 可以携带 `limit: N`。pig 自身没有分页，因此 relay 自己返回最新的 N 条消息——该会话没有 pig 附着时从磁盘缓存取，否则裁剪 pig 的回复——并在 `data` 中增加两个字段：

| 字段 | 含义 |
| --- | --- |
| `historyStart` | 整个对话记录中第一条返回消息的索引；`0` 表示没有更早的消息了 |
| `historyTotal` | relay 为该会话保存的消息总数 |

窗口会向前回退到开启其第一轮的 `user` 消息（最多 24 条消息），这样一页绝不会以 `toolResult` 开头而其 `toolCall` 落在上一页。不带 `limit` 的 `get_messages` 会整份应答，因此旧客户端不受影响；磁盘回退同样返回整个会话，App 可以加载全部轮次。

## relay → 客户端

| 帧 | 含义 |
| --- | --- |
| `relay_hello` | 认证通过；复用已有 pig 时 `resumed=true` |
| `relay_log` | pig stderr 的一行 |
| `relay_exit` | pig 已退出；`code` 是退出状态 |
| `relay_error` | relay 级错误（不是 agent 错误） |
| `relay_projects` | 项目列表 |
| `relay_sessions` | 会话列表（`path`、`id`、`cwd`、`name`、`preview`、`updated`、`size`） |
| `relay_session_renamed` | 重命名成功 |
| `relay_session_deleted` | 删除成功 |
| `relay_session_error` | 重命名/删除失败（`message`） |
| `relay_ui_pack` | UI pack 快照 |
| `relay_settings` | 解析后的 `~/.pig/agent/settings.json` 以及可写键列表 |
| `relay_settings_error` | 设置读取/写入失败（`message`） |
| `relay_model_override` | 已写入 `models.json` 的上下文窗口（回显） |
| `relay_model_override_error` | 覆盖被拒绝（`message`） |
| `relay_resume` | 重连后重放进行中的 assistant 文本 |
| `relay_tool` | 重连后重放运行中工具的进度 |
| `relay_restarted` | pig 已重新启动 |

其余一切都是 pig RPC 帧（`response`、`message_update`、`extension_ui_request`、……）。

App 将 relay 级故障作为提示保留在对话记录中：它们会追加到 `files/notices/<session>.jsonl`，并随历史记录一起重放，因此能在重连后保留。pig 自身记录的故障（带 `errorMessage` 的 assistant 消息）则改为从 pig 的历史记录重放。

## 设置

不带 `set` 的 `relay_settings` 返回原始的 `~/.pig/agent/settings.json` 对象（文件缺失时为空对象），以及 `editable`——App 可写入的键的排序列表。带 `set` 时，只有允许列表中的点分路径会合并进文件；未知键和无效类型/枚举会被静默丢弃，因此过期的 App 永远不会破坏无关设置。写入是原子的（`tmp` + 重命名），并保留 `settings.json.bak-<timestamp>-pre-app` 备份。

可写键：`compaction.enabled`、`compaction.reserveTokens`、`compaction.keepRecentTokens`、`cacheWarming`、`hideThinkingBlock`、`retry.enabled`、`retry.maxRetries`、`retry.baseDelayMs`、`retry.maxAgentDelayMs`、`defaultThinkingLevel`、`thinkingBudgets.*`。

## 模型覆盖

`relay_model_override` 在 `~/.pig/agent/models.json` 中固定每个模型的上下文窗口：

```json
{"type": "relay_model_override", "id": 7, "provider": "eu",
 "model": "wb2api/cn:deepseek-v4.1-flash", "contextWindow": 1000000}
```

它写入 `providers.<provider>.modelOverrides.<model>.contextWindow`，同时保留其他覆盖键（`reasoning`、`thinkingLevelMap`、……）。`contextWindow` 为 `0` 时只删除该键（以及空的覆盖对象）。provider 必须已存在于 `models.json` 中；未知 provider 和超出 `0…10 000 000` 的窗口都会被拒绝。写入纪律与设置帧相同：原子重命名加 `models.json.bak-<timestamp>-pre-app`。

PiG 在启动时读取 `models.json`，并且不会在 `set_model` 时重新读取，因此新窗口只对之后启动的 agent 生效——运行中的 agent 保留其旧值。这就是 App 在覆盖成功后接着发送 `relay_restart`（见上文）的原因：relay 停止 agent 并重新打开同一 session 文件，因此对话记录得以保留，固定值也随之生效。`get_session_stats` 会报告 `contextUsage.contextWindow`，App 正是拿它与固定的窗口比较（也是它驱动 `context` 绑定上的 `⏳` 标记）。

这些值由 pig 在进程启动时读取，因此改动只对之后新启动的会话生效；运行中的 pig 保留其已加载的值。

## 供应商管理

App 原生的 模型供应商 页面通过这些帧读写
`~/.pig/agent/custom-endpoints.json`。API Key 只写不读：`relay_providers` 只报告
`hasKey`，从不返回 Key；保存时不带 `apiKey` 就保留已存的 Key。

| 帧 | 字段 | 响应 |
| --- | --- | --- |
| `relay_providers` | `id` | `relay_providers`（`providers`、`modelsProviders`、`path`、`offline`） |
| `relay_provider_save` | `id`、`entry` | `relay_provider_saved`（`provider`、`path`，可选 `warning`） |
| `relay_provider_delete` | `id`、`entry.id` | `relay_provider_deleted`（`provider` 为 id） |
| `relay_provider_models` | `id`、`entry`（草稿：`id`、`baseUrl`、`api`，可选 `apiKey`/`modelsUrl`） | `relay_provider_models`（`provider`、`models`、`fetchedAt`） |

任何失败都返回 `relay_provider_error`，带 `op`（`list` / `save` / `delete` /
`refresh`）和 `message`。

保存时的 `entry`：

```json
{"id": "gproxy", "name": "g", "baseUrl": "https://…/v1",
 "api": "openai-completions", "apiKey": "sk-…", "clearKey": false,
 "auto": true, "modelsUrl": "",
 "models": [{"id": "m1", "name": "m1", "contextWindow": 128000, "maxTokens": 8192}],
 "cacheModels": [ … ]}
```

- `auto: true` 写入 `models: "auto"`；`cacheModels` 成为该供应商在
  `custom-endpoints.cache.json` 里的缓存，会话无需联网即可注册模型。
  `auto: false` 写入显式的 `models` 数组（空数组会被拒绝），同时把
  `cacheModels` 留在缓存里供下次选择窗使用。
- 表单不编辑的键（`headers`、`oauth`、`defaults`、刷新选项）会保留。写入是原子的，
  并保留 `custom-endpoints.json.bak-<时间>-pre-app`。
- `relay_provider_models` 只获取模型，不碰文件。relay 使用与会话一致的请求头规则
  （`Authorization: Bearer`；Anthropic 用 `x-api-key` + `anthropic-version`；
  Google 用 `?key=`；显式 `headers`/`modelsHeaders`），在 Termux 里展开
  `$ENV`/`${ENV}`/`!command`，并在设置 `PIG_OFFLINE`/`PI_OFFLINE` 时拒绝。
- relay 不负责把供应商注册进运行中的会话：App 在保存/删除成功后发送
  `relay_restart`，新的 pig 启动时由 `custom-endpoints` 扩展读取文件。

## 备份与恢复

`~/.pig` 在 Termux 的私有数据目录里，App 无法直接读写，所以由 relay 充当文件
服务器。备份是一个 **gzip tar**，条目名相对于 Termux home
（`$HOME/.pig/agent/...`、`.pig/state/...`、`.pig/android-ui/...`），按 base64
分块在同一条连接上流式发送：

```
→ {"type":"relay_backup","id":"b1","sessions":true,"ui":true}
← {"type":"relay_backup_start","id":"b1","name":"pig-backup-20261027-120000.tar.gz","entries":312,"bytes":68123456}
← {"type":"relay_backup_chunk","id":"b1","seq":0,"data":"H4sI…"}
← {"type":"relay_backup_chunk","id":"b1","seq":1,"data":"…"}
← {"type":"relay_backup_done","id":"b1","name":"…","entries":312,"bytes":9123456,"sha256":"…"}
```

- `start` 里的 `bytes` 是未压缩大小（用于进度条）；`done` 里的 `bytes` 和
  `sha256` 对应实际发送的压缩流。
- `sessions: false` 不打包 `agent/sessions`（归档的大头）；`ui: false` 不打包
  `android-ui`。密钥（`agent/auth.json`、`agent/models.json`、
  `agent/custom-endpoints.json`）始终包含，所以备份是敏感文件。
- 缓存与噪音不打包：`crash-output/`、`backups/`、`.log` 文件、
  `node_modules/`、`__pycache__/`、`.git/` 和符号链接。
- relay 自己写的、启动时会重建的内容也不打包：带 `.embedded-hash` 的目录
  （`agent/pigkit/<pkg>` 源码和 `agent/extensions/plan`），以及根 marker 存在时的
  pigkit `go.mod`/marker。旁边没有 marker 的用户包会保留。二进制
  （`$PREFIX/bin/pig`、`pig-relay`）和 `~/.pig/cache` 从来就不在归档里。
- 每个 chunk 最多 48 KiB 原始字节；归档上限 1 GiB，同一时间只允许一个传输。

恢复就是同样的字节反着走。relay 先写入暂存文件，校验声明的 `size` 和
SHA-256，通过后才动现有数据：

```
→ {"type":"relay_restore_begin","id":"r1","name":"pig-backup.tar.gz","size":9123456}
← {"type":"relay_restore_ready","id":"r1","max":1073741824}
→ {"type":"relay_restore_chunk","id":"r1","data":"H4sI…"}        （重复）
→ {"type":"relay_restore_end","id":"r1","sha256":"…"}
← {"type":"relay_restore_done","id":"r1","entries":312,"bytes":9123456,"backup":"/…/.pig/backups/pig-auto-before-restore-20261027-120500.tar.gz"}
```

恢复不会带回 Go 编译缓存（`~/.pig/cache`）：relay 会重新释放内置源码，pig 第一次
启动时重新编译（和全新安装做的事一样）。

解压前，relay 会先把当前数据完整备份到 `~/.pig/backups/`。成功后会清理
`settings.json`：删掉指向 relay 自己安装的扩展（目前是 `plan`）另一份副本的 package
条目，并保留 `extensions/plan`。否则来自开发机 checkout 的备份会让 pig 以
`Flag "--plan" conflicts` 退出，App 只能看到反复重连/报错。被移除的条目放在
`relay_restore_done` 的 `settingsFixed` 里，非致命的清理问题放在 `warning` 里。解压只接受 `.pig/`
下的条目，拒绝绝对路径和 `..`，从不创建符号链接、硬链接或设备文件；现有但
归档里没有的文件会保留（合并，而不是清空）。恢复成功后所有 pig 进程都会停止
——它们在启动时缓存设置、模型和密钥 —— 下一次 RPC 会用恢复后的文件重新启动。
还在跑的会话会被结束，不会写回。

## UI pack

relay 每 800 ms 扫描一次 `--ui-dir`。隐藏文件、符号链接、二进制文件以及大于 512 KiB 的文件都会被跳过。当内容哈希变化时，它推送：

```json
{"type":"relay_ui_pack","hash":"<sha256>","files":{"manifest.json":"…","theme/colors.json":"…"},"assets":{"assets/logo.png":"<base64>"},"assetHashes":{"assets/logo.png":"<sha256>"}}
```

`files` 将斜杠分隔的相对路径映射到文本。哈希覆盖排序后的 `path\0content\0` 对，因此新增、删除或编辑任何文件都会触发推送。目录缺失会被当作空 pack。

M1 定义了 pack 的 schema（`manifest.json`、`theme/`、`layout/`、`panels/`、`renderers/`）；参见 `examples/ui-pack/`。

## 生命周期与重连

relay 拥有 `pig --mode rpc` 进程，并且一次只服务一个客户端。客户端断开时，pig **继续运行**，因此仍在流式输出的回合不会被中止。新客户端附着到同一个 pig：

- relay 发送 `relay_hello`，带 `resumed: true` 和相同的 `pigPid`；
- 握手后，它重放 `relay_resume`（包含目前已累积的 assistant 文本）、运行中工具的 `relay_tool`，以及任何未应答的 `extension_ui_request`。重放只携带文本，因此图像块（`{"type":"image",…}`）改为通过 `get_messages`/`message_end` 到达；
- 客户端用 `get_state`/`get_messages` 重新同步其对话记录，并且在 `isStreaming` 为 true 时不得调用 `switch_session`。
- 尚未附着任何会话的 relay 会以 `{"messages": []}` 和 `sessionPath: ""` 应答 `get_messages`。客户端不得因该应答清空对话记录：先等 `get_state`（并用 `switch_session` 恢复记住的会话），再渲染历史记录。`get_state` 携带 `messageCount`，客户端可借此发现自己缺少消息。

如果 pig 退出，relay 发送 `relay_exit`；下一次附着重启一个全新的 pig。`relay_restart` 会显式停止并替换它。不会传 `--no-session`，因此会话持久化在 `~/.pig/agent/sessions` 下。
