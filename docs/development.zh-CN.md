[English](development.md) · **简体中文**

# 开发

构建 relay 和 App，手动运行客户端，并从 shell 驱动它。这些都不需要用来*使用* App —— 使用说明见 [install.zh-CN.md](install.zh-CN.md)。

## 构建 relay

```bash
cd relay
go build -trimpath -ldflags="-s -w -X main.buildVersion=dev" -o "$PREFIX/bin/pig-relay" .
```

`main.buildVersion` 就是 `pig-relay --version` 在协议后面打印的版本
（`relay/1 1.1.27`）；发布流程会把 tag 写进去，Termux 安装脚本发现版本与 App
不一致时会替换。

relay 随二进制分发它自带的扩展：`relay/embedded/` 是 `extensions/` 的副本，二进制内嵌，
首次启动时解出：

- `extensions/plan` → `~/.pig/agent/extensions/plan/`，并调用 `pig install` 注册；
  用户自己配置的 plan 扩展（仓库副本、其它包）优先于内嵌副本。
- `extensions/pigkit`（App 自己界面背后的模块：`/provider-ui` 供应商窗口、自定义端点、
  model-speed、memory、questionnaire、codegraph）→ `~/.pig/agent/pigkit/`，并把每个包以
  `pigkit/<name>` 追加到 `settings.json` 的 `packages`。没有 `.embedded-hash` 标记的包目录
  视为用户自己的：不覆盖、也不重复注册；已有其它路径提供同名包时也不再加。
- `examples/ui-pack-ember`（relay 的默认 pack）→ `~/.pig/android-ui`，源文件在
  `relay/embedded/ui`。APK 内置的是另一套（`examples/ui-pack-compact`），所以推送的
  pack 和内置 pack 是两个不同外观。只补缺失文件（手改过的主题/布局保留），并且**永远不包含
  `windows/`**：窗口由拥有它的扩展渲染（`provider-ui` 按用户的 `custom-endpoints.json`
  写 `windows/providers.json`）。CI 的 `scripts/check-ui-packs.sh` 会拒绝提交
  生成的供应商窗口——曾经把 `~/.pig/android-ui` 拷进示例，结果真实供应商 URL 进了 APK。

`relay/embedded/` 是生成产物——改完扩展要跑 `sh scripts/sync-embedded-extensions.sh`
并提交；CI 会在它过期时报错。

## 新机模拟

`tools/fresh-device-sim/sim.sh` 用真实的 Termux 用户空间跑「Termux 那一半」：官方
`termux-packages` bootstrap 解压到 `~/.cache/...` 后用 `proot` 运行，所以安装脚本看到的
是空 `$PREFIX`、没有 `pig`、没有 Go、全新的 `$HOME`。「App 那一半」由
`tools/fresh-device-sim/app-sim.py` 扮演：发 App 会发的那些帧，检查 UI pack、pigkit 命令、
供应商窗口，以及 `/providerui.save`（端点可用/不可用两种情况）。

```sh
tools/fresh-device-sim/sim.sh all      # 清空设备 → 安装 → 回放 App
tools/fresh-device-sim/sim.sh destroy  # 占约 1 GB（bootstrap + Go 工具链）
```

需要联网（bootstrap、Termux 软件包、官方 pig 安装脚本），手机上约五分钟；不进 CI。
分步命令和「哪些是 proot 的假象而不是 bug」见该目录的 README。

### relay 与 pig 的日志

App 读不到 logcat，也读不到 Termux 的滚动缓冲，所以 relay 把能给的都发过来：每条
`logf` 和 pig 的每一行 stderr 都以 `relay_log` 帧发出（`source` 为
`relay`/`pig`，`level` 为 `info`/`error`），最近 400 行缓存在内存里，握手后作为
`relay_logs` 回放；pig 崩溃时 `relay_exit` 会带上 stderr 末尾。App 把失败弹成
toast、全部写进诊断日志，并在「设置 → 诊断日志 → 查看」里实时滚动显示。

## 发布构建

```bash
./gradlew :app:assembleRelease -PversionCode=100 -PversionName=1.0.0
```

R8 与资源压缩已开启，因此 APK 约为 1.5 MB，而不是（debug 的）约 17 MB，且在本机上启动约 240 ms 而不是约 880 ms。CI 在每次推送时构建它，并把 `pig-release-apk` 与 debug 产物一起上传。

签名：`PIG_RELEASE_STORE_FILE` / `PIG_RELEASE_STORE_PASSWORD` /
`PIG_RELEASE_KEY_ALIAS` / `PIG_RELEASE_KEY_PASSWORD` 可切换到商店密钥。
没有它们时，构建使用 CI 密钥，也就是 debug APK 所用的同一个密钥，因此发布构建可以覆盖已安装的 debug 安装（`pm install -r`）并可用于性能测试。发布给别人则需要商店密钥，因为签名必须在各次更新之间保持稳定。

## 构建 App

本地构建需要 JDK 和 Android SDK，而这里的 Termux 没有。CI 负责构建 APK：

1. 推送到 `main`（或开 PR / 手动运行工作流）。
2. 从 Actions 运行记录中下载 `pig-debug-apk` 产物。
3. `adb install -r app-debug.apk`（或在设备上打开该文件）。

debug 工作流是 `.github/workflows/android.yml`：JDK 17、Gradle wrapper 8.9、`assembleDebug`、产物上传。debug 构建使用仓库中提交的 `keystore/ci-debug.p12`（别名 `androiddebugkey`，密码 `android`），因此后续 CI 构建可以互相覆盖安装。

发布构建使用 `.github/workflows/release.yml`，由 `v*` tag 或手动触发。密钥库来自 `RELEASE_KEYSTORE_BASE64`、`RELEASE_STORE_PASSWORD`、`RELEASE_KEY_ALIAS` 和 `RELEASE_KEY_PASSWORD` 这几个 Actions secret；版本名是去掉 `v` 的 tag，版本号是提交数。本地备份位于 `~/.pig-android-release/pig-release.p12`（不在仓库中）。打 tag 还会创建一个附带 APK（以及 `pig-relay-android-arm64`、`install-termux.sh`）的 GitHub Release。每个版本可以带自己的说明：`release-notes/<tag>.md` 会排在自动生成的「What's Changed」之前，见 [release-notes/README.md](../release-notes/README.md)。release 密钥与 debug 密钥不同，因此要在已安装 debug 构建的设备上安装 release APK，需要先卸载 debug App 一次。

## 运行

通常你只需打开 App：它用存储的 token 启动 relay 并连接。调试时，用 App 使用的同一个 token 自行启动 relay（该 token 存储在 App 的 `pig` SharedPreferences 中）：

```bash
pig-relay --port 8791 --token <token> --cwd ~ --ui-dir ~/.pig/android-ui
```

relay flags：

| Flag | 默认值 | 含义 |
| --- | --- | --- |
| `--listen` | `127.0.0.1` | 绑定地址（保持在回环上） |
| `--port` | `8791` | TCP 端口 |
| `--token` | 自动生成 | 共享 token |
| `--token-file` | | 从文件读取 token |
| `--cwd` | `$HOME` | pig 工作目录 |
| `--ui-dir` | `~/.pig/android-ui` | UI pack 目录 |
| `--pig` | `pig` | pig 二进制文件 |


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


## 从 shell 驱动 App（debug 构建）

debug 构建会注册 `com.linuxwff.pigapp/.DebugReceiver`，因此你可以不碰屏幕就驱动客户端。始终传 `-n`；App 可能处于后台，而隐式广播不会投递给缓存中的 App。

```bash
# current connection / model / messages (logcat tag PigDebug)
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_STATE
logcat -d -s PigDebug

# send a prompt
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_PROMPT --es text "Reply with exactly OK"

# switch to a session file
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_SWITCH --es path /path/to/session.jsonl

# model / thinking
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_MODELS
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_SET_MODEL --es provider deepseek --es model deepseek-flash
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_THINKING --es level high

# reconnect (relaunches the relay if needed)
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_CONNECT

# abort the active turn
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ABORT

# answer a pending confirm/select/input dialog
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ANSWER --es value "Allow"
am broadcast -n com.linuxwff.pigapp/.DebugReceiver -a com.linuxwff.pigapp.DEBUG_ANSWER --ez confirmed true
```

`DEBUG_STATE` 还会写入 `/storage/emulated/0/Android/data/com.linuxwff.pigapp/files/state.txt`（root 可读），从而不必依赖 logcat。

`DEBUG_PROMPT` / `DEBUG_FORM` 作用于**当前会话**。发送测试 prompt 前先把 App 切换到草稿会话；否则它们会落进当前打开的任何对话里。
