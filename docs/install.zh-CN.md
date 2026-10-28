[English](install.md) · **简体中文**

# 在 Termux 中安装后端

Android App 是前端。`pig` 和 `pig-relay` 运行在 Termux 里。

## 一条命令

从 F-Droid 安装 Termux（Play Store 版本已过时），打开一次让 `pkg` 完成初始化，然后：

```bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

不需要先装任何东西：Termux 的 bootstrap 自带 `curl`（脚本里也会再检查一次）。
安装脚本会在**第一次 `pkg install` 之前**就把主仓库换成国内镜像，所以后面下载 Go
工具链时已经走镜像了；App 的一键安装跑的就是同一条命令。

带上 App 的版本号可以装与它同批发布的 relay（App 一键安装会自动这样传；手动写法）：

```bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/download/v1.1.27/install-termux.sh | sh -s -- v1.1.27
```

它做六件事：

1. 当 Termux 主仓库还指向 `packages.termux.dev`、且这次确实要用 `pkg install`
   （缺 curl 或缺 Go）时换成国内镜像；默认清华 TUNA，`PIG_TERMUX_MIRROR=…` 换别的，
   `PIG_NO_MIRROR=1` 跳过，`PIG_FORCE_MIRROR=1` 强制换；旧文件备份为 `sources.list.bak-pig`；
2. 缺少 `pig` 时，用官方 `https://pi-in-go.dev/install.sh` 安装；
3. 缺少 Go 工具链时用 `pkg install golang` 安装——relay 首次启动要用它编译自带的 plan 扩展；
4. 下载 `pig-relay-android-arm64` 到 `$PREFIX/bin/pig-relay`，版本不同会原子替换
   （`pig-relay --version` 会打印版本号，所以旧版本是被升级而不是被跳过）；不带 tag
   运行时会先问 GitHub「latest 是哪个 tag」，所以 `sh install-termux.sh` 也会升级旧 relay
   —— 旧 relay 里没有新的内嵌扩展；
5. 创建 `~/.pig/android-ui/{theme,layout,windows}`；relay 启动时会把默认 UI pack
   （`manifest.json`、主题、布局、文案、导航）**补齐**到这个目录——只补缺失文件，
   你改过的不会被动——否则 App 收到的 pack 缺少 `manifest.json`，只能报
   「UI pack 未应用」并退回内置主题；
6. relay 二进制里自带 `pigkit` 扩展（App 里「模型供应商」窗口、自定义端点、model-speed、
   memory、questionnaire、codegraph）：首次启动时解出到 `~/.pig/agent/pigkit/`，并把每个包
   注册进 `settings.json`。不需要再单独下载；你自己已有的同名目录不会被覆盖。

它还会在 `~/.termux/termux.properties` 中启用 Termux 的外部命令开关（保留 `.bak-pig` 备份），并运行 `termux-reload-settings`。**打开一次 Termux App** 让该属性生效，授予 Pig App `com.termux.permission.RUN_COMMAND` 权限，然后按重试。

最后它用 RPC 模式启动一次 `pig` 并请求扩展列表：工具链装不上、扩展编译不出来时会当场报出来，而不是过一阵变成「App 里只有内置命令」。失败时会自动跑一次
`pig update --extensions` 再探一次（缓存的失败构建只有强制重建才会好）。

探测之前脚本会运行 `pig-relay --prepare`：解出内置 pigkit 源码和 plan 扩展并注册，
所以干净的 Termux 不会出现「扩展加载成功」但其实 `/plan`、`/provider-ui` 还不存在的情况。
探测本身也会按名字检查这些路径，不只看 `success:true`。

安装脚本分为 **9 个编号阶段**（环境、curl、Termux 开关、pig、Go、relay、UI 目录、
扩展、校验），每一阶段都写入 `~/.pig/install-status.json`（`state` 为
`running` / `done` / `error`）。relay 1.1.36+ 会监视这个文件：安装进行中
**不启动 pig**，并把阶段以 `relay_install` 帧转给 App，所以设置对话框显示
`步骤 N/9 · …` 和真实进度条，而不是按时间猜。任何阶段失败（包括扩展加载不了）
都会写 `state: error` 并以非 0 退出——App 会明确报错，不会连上一个半成品后端。

官方 pig 安装器自己的下载没有超时；脚本会给它 **300 秒**超时，失败后改用
`PIG_MIRRORS`（默认 gh-proxy / ghfast）下载 release tarball，所以 GitHub CDN 卡死
不会让安装永远停在第 4 步。

所有下载都带重试，并在 TLS/HTTP2 断连（curl 退出码 35/53/56 等）时自动退回
HTTP/1.1；下载先写 `.part`、校验非空（relay 还校验 ELF magic）后才替换，所以失败
不会留下半截脚本或半个二进制。

### 为什么不直接用 App 内的按钮？

`allow-external-apps=false` 时 `RUN_COMMAND` 本身会被拦截。在干净的 Termux 上，用户不打开该开关，App 就无法运行安装脚本，因此设置对话框会给出那一行命令供你粘贴到 Termux，并带 **复制命令** 和 **打开 Termux** 两个按钮；安装期间对话框显示分阶段进度（实时输出在 Termux 窗口里，relay 一答上就自动切到已连接）。

App 现在会读 Termux 对每条 `RUN_COMMAND` 回传的结果：被 `allow-external-apps` 拦截、或者 `pig-relay` 还不存在，都会立刻给出对应提示，而不是看起来什么都没发生。

如果卡在开关这一步，对话框会给一条短得多的命令，只做「允许 App 执行命令」这一件：

```bash
mkdir -p ~/.termux; f=~/.termux/termux.properties; sed -i '/allow-external-apps/d' "$f" 2>/dev/null; echo 'allow-external-apps = true' >> "$f" && termux-reload-settings
```

纯 shell，离线也能跑；会先删掉旧的/被注释的 `allow-external-apps` 行再追加，重复执行安全。喜欢可读版本的话，每个 release 里也有同名脚本：

```bash
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/enable-external-apps.sh | sh
```

回到 App 点「一键安装」就会走有进度的那条路。开关打开并且 Termux 被打开过一次后，一键安装即可使用。

## 手动安装

```bash
curl -fsSL https://pi-in-go.dev/install.sh | sh
curl -fsSL -o "$PREFIX/bin/pig-relay" \
  https://github.com/linuxwff789/pig-android/releases/latest/download/pig-relay-android-arm64
chmod 755 "$PREFIX/bin/pig-relay"
mkdir -p ~/.pig/android-ui/{theme,layout,windows}

pig --version
pig-relay --version
```

## App 启动了什么

```
App ── RUN_COMMAND ──▶ Termux RunCommandService
                         └─ pig-relay --port 8791 --token … --cwd ~
App ── TCP 127.0.0.1:8791 ──▶ pig-relay ── stdio ──▶ pig --mode rpc
```

如果 App 无法连接，其设置对话框会显示一行命令和一个一键安装按钮，通过 Termux 运行同一个脚本。

后端装好后，全新的 `~/.pig` 仍然没有模型 provider。打开 Pig，用首次启动卡片里的
**添加供应商**（或 设置 → 连接 → 模型供应商），或导入备份，再选择模型；内置 provider
可在 Termux 里用 `pig login` 配置。

只更新了 APK 时，旧 relay 还在跑。设置 → 连接 会显示
`relay <版本> · App <版本>`；relay 较旧时有 **更新 relay** 按钮，重跑安装脚本
（按 App 的 tag 安装，结束前会编译扩展）并自动重启 relay。relay 较新时不会动它——
安装脚本只升不降。

## 故障排查

| 症状 | 修复 |
| --- | --- |
| `pig-relay: not found` | 重新运行安装脚本；它必须位于 `$PREFIX/bin`。 |
| 12 秒后连接被拒绝 | relay 未运行，或 Termux 拒绝了 RUN_COMMAND。检查 `allow-external-apps` 并打开一次 Termux。 |
| `cannot start pig` | `pig` 缺失或不可执行：`command -v pig`，然后 `pig --version`。 |
| `pkg: command not found` | 你不在 Termux 中，或 Termux 太旧。从 F-Droid 重新安装。 |
| `curl: not found` | `pkg install -y curl`。 |
| `curl: (35) TLS connect error: error:00000000:lib(0)::reason(0)` | 代理/中间盒掐断了握手。App 会先试 GitHub 再试 jsDelivr；也可以自己下脚本：`curl -fsSL --tls-max 1.2 https://cdn.jsdelivr.net/gh/linuxwff789/pig-android@main/scripts/install-termux.sh -o "$PREFIX/tmp/i.sh"`。relay 二进制可设 `PIG_RELAY_MIRRORS`（URL 前缀，如 `https://ghfast.top/`）再跑脚本。 |
| `github.com` 连不上但 API 正常 | 安装脚本会退回 `api.github.com/.../releases/assets/<id>`（它再跳到 `objects.githubusercontent.com`，又是另一个主机）。脚本本身可以绕开 `github.com`：`curl -fsSL -H 'Accept: application/vnd.github.raw' "https://api.github.com/repos/linuxwff789/pig-android/contents/scripts/install-termux.sh?ref=main" -o "$PREFIX/tmp/i.sh"`。 |
| GitHub 返回 `403` / `429`，或 App 里提示「GitHub 限流」 | 更新检查已改走 `releases.atom` 和 `releases/latest` 的跳转，不再调用 `api.github.com`（未认证时按 IP 限制 60 次/小时，移动网络下多人共享一个出口 IP）。release 资产本身是普通下载；若仍返回 429，稍后再试，或带 `PIG_RELAY_MIRRORS=https://ghfast.top/` 运行安装脚本。`github.com` 连不上时脚本会退回 API，此时 `PIG_GITHUB_TOKEN=<pat>`（公开仓库无需任何 scope）可把额度提到 5000 次/小时。 |
| 刷新模型列表报 `dial tcp: lookup … on [::1]:53: … connection refused` | Android 没有 `/etc/resolv.conf`（Termux 的在 `$PREFIX/etc` 下）；pig 通过 libc 解析，而 CGO-free 的 relay 读不到。1.1.52+ 会读 Termux 的 resolv.conf，并回退到公共 DNS；升级后重启 relay 即可，`PIG_DNS="1.1.1.1 8.8.8.8"` 可覆盖服务器列表。 |
| 重装后残留的 relay / token | `pkill -x pig-relay`，然后按重试。如果 relay 从未有过客户端，它会在 token 错误时退出。 |
| App 不显示模型/窗口 | 检查 `ps -A | grep pig`；重启 App。 |
