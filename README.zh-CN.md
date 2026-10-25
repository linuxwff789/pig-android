[English](README.md) · **简体中文**

<p align="center">
  <img src="docs/images/hero.png" alt="Pig Android —— 口袋里的真 PiG agent" width="880">
</p>

<h1 align="center">Pig Android</h1>

<p align="center">
  <b>口袋里的真 PiG agent。</b><br>
  原生 Compose 客户端 —— 模型、工具和 API key 全都留在 Termux 里。
</p>

<p align="center">
  <a href="https://github.com/linuxwff789/pig-android/releases/latest"><img src="https://img.shields.io/github/v/release/linuxwff789/pig-android?label=release&color=A78BFA" alt="最新发布"></a>
  <a href="https://github.com/linuxwff789/pig-android/actions/workflows/android.yml"><img src="https://github.com/linuxwff789/pig-android/actions/workflows/android.yml/badge.svg" alt="构建状态"></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/UI-Kotlin%20%C2%B7%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin 与 Compose">
  <img src="https://img.shields.io/badge/relay-Go-00ADD8?logo=go&logoColor=white" alt="Go">
</p>

## 不是套壳，也不是重写

App 只是薄前端：它从不直接和模型供应商通信，也从不持有 API key。每一轮都由
Termux 里的原版 `pig` 二进制执行，App 只负责渲染结果。

```
┌──────────────┐   TCP 127.0.0.1:8791   ┌────────────┐   stdio JSONL   ┌──────────────┐
│  Pig app     │ ─────────────────────► │ pig-relay  │ ──────────────► │ pig --mode rpc│
│  (Compose)   │ ◄───────────────────── │   (Go)     │ ◄────────────── │   (Termux)    │
└──────────────┘   one JSON per line    └────────────┘                 └──────────────┘
                                          │
                                          └── watches ~/.pig/android-ui and pushes UI packs
```

`pig-relay` 是唯一的桥：回环 socket、每安装实例的 token，以及把 PiG 自己的 RPC
帧原样转发。没有配对界面 —— App 自己通过 Termux `RUN_COMMAND` 拉起 relay。
关掉 App，agent 照常干活；跑到一半再打开，回答从断开处接着流。

## 功能一览

| | |
| --- | --- |
| 💬 **真正的对话记录** | 流式 Markdown —— GFM 标题、列表、带对齐的表格、代码围栏 —— 外加可折叠的 `思考` 块、复制按钮和 `回到底部` 胶囊按钮。 |
| 🧰 **工具卡片** | 收起 / 摘要 / 展开（长按胶囊按钮可应用到所有卡片），`write` 显示行数和字节数，`edit` 显示新增/删除计数和彩色 diff。 |
| 🧩 **扩展对话框** | 原生应答 `confirm` / `select` / `input` / `editor`，跟随 pack 主题，正文可选中可复制；点一下聊天区就能把提示暂存，再从横幅调回来。 |
| 📋 **计划清单** | 任何扩展发出的 `setWidget` 计划都会变成原生卡片，带进度条和 `☑ / ☐` 步骤 —— 没有注册表，也没有白名单。 |
| 🗂 **会话** | 抽屉支持搜索、重命名、删除；每个会话独立草稿；正在新建的会话也不会丢掉你输入的文字。 |
| 🎛 **模型** | 可搜索的选择器，显示上下文、思考档位和图片支持；可钉默认模型；在 `ctx` 行上修正供应商报错的 `contextWindow`。 |
| 🖼 **图片双向通行** | 工具与助手图片、系统相册选择器、Markdown 图片，以及能把图存进 `Pictures/Pig` 的全屏查看器。 |
| 🎨 **UI pack** | 主题、字体、布局、文案、图标、导航入口和窗口都从 `~/.pig/android-ui` 热重载 —— 整站换肤，无需重新构建。 |
| 🔔 **通知** | 回复完成、需要你操作、出错、上下文已压缩 —— 分通道、可单独选提示音、可逐项开关。 |
| 🔒 **密钥不外流** | 只监听回环并校验 token；`~/.pig/agent/auth.json` 对 App 完全不可见。 |

深入细节 —— 重连与半途接管、图片管线、思考块、缓存数字、各项调优开关 —— 见
[docs/features.zh-CN.md](docs/features.zh-CN.md)。

## 安装

APK 只是前端。先从 [F-Droid 安装 Termux](https://f-droid.org/packages/com.termux/)
（Play 商店那个版本已废弃），打开一次让 `pkg` 完成初始化，然后执行：

```bash
pkg install -y curl
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

脚本会启用 `allow-external-apps`（保留备份）、用官方安装器装好 `pig`、从最新发布
下载 `pig-relay-android-arm64`，并创建 `~/.pig/android-ui`。之后再打开一次
Termux 让属性生效，然后安装 APK 并授予
`com.termux.permission.RUN_COMMAND` 权限。

如果 App 弹出了配置对话框，里面就是同样两行命令，带复制按钮。**一键安装**只在
`allow-external-apps` 已启用时可用；否则把这两行手动粘进 Termux。手动安装路线和
故障排查见 [docs/install.zh-CN.md](docs/install.zh-CN.md)。

## 更新

**设置 → 关于 → 检查更新** 会把已安装版本和 GitHub 最新发布对比。直接用新的
`app-release.apk` 覆盖安装即可 —— 签名密钥相同，数据不会丢。

正在运行的进程不会自动换用新的 `pig-relay`：重跑安装脚本（或把二进制复制进
`$PREFIX/bin`），再用 **设置 → 连接 → 重启 relay**。

## 最近更新

- **1.1.1** —— relay token 不一致会自愈：重装后 App 从 prefs 重读 token，
  必要时用正确的 token 重启 relay，而不是显示 `auth failed: bad token`。
- **1.1.0** —— 图片全面打通：工具与助手图片、输入框里的相册选择器、Markdown
  图片，以及支持存进相册的查看器。
- **1.0.0** —— 首个 minified 发布版：启动图标、可选提示音的会话通知、思考块、
  计划组件、用量统计。

完整历史见 [releases](https://github.com/linuxwff789/pig-android/releases)。

## 文档

| 文档 | 内容 |
| --- | --- |
| [install.zh-CN.md](docs/install.zh-CN.md) | Termux 后端：脚本、手动安装、故障排查 |
| [ui-pack.zh-CN.md](docs/ui-pack.zh-CN.md) | 全部 pack 键：颜色、字体、布局、文案、窗口 |
| [app-ui.zh-CN.md](docs/app-ui.zh-CN.md) | `app-ui/1`：用 JSON 描述、由 Compose 渲染的窗口 |
| [plan-widget.zh-CN.md](docs/plan-widget.zh-CN.md) | `setWidget` 计划契约 |
| [protocol.zh-CN.md](docs/protocol.zh-CN.md) | relay 线协议（`relay/1`） |
| [features.zh-CN.md](docs/features.zh-CN.md) | 行为详解：重连、图片、思考、调优 |
| [development.zh-CN.md](docs/development.zh-CN.md) | 本地构建、运行与调试 |

仓库里还有几个可选的 Go 扩展：[plan](extensions/plan/README.zh-CN.md)（只读规划
模式，带可打勾的计划卡片）、[rtk](extensions/rtk/README.zh-CN.md)（压缩工具输出）
和 [subagent](extensions/subagent/README.zh-CN.md)（拥有独立上下文的子 agent）。

## 安全

- relay 只监听 `127.0.0.1`，并要求首帧携带 token。
- `allow-external-apps=true` 会让任何持有 `RUN_COMMAND` 权限的 App 在 Termux
  里执行命令。这是免配对启动路径的代价 —— 不想付这个代价，可以改用 Termux
  service 启动 relay。
- API key 留在 Termux（`~/.pig/agent/auth.json`），App 永远看不到。
