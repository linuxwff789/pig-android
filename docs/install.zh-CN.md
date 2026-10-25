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
   （`pig-relay --version` 会打印版本号，所以旧版本是被升级而不是被跳过）；
5. 创建 `~/.pig/android-ui/{theme,layout,windows}`；
6. relay 二进制里自带 `pigkit` 扩展（App 里「模型供应商」窗口、自定义端点、model-speed、
   memory、questionnaire、codegraph）：首次启动时解出到 `~/.pig/agent/pigkit/`，并把每个包
   注册进 `settings.json`。不需要再单独下载；你自己已有的同名目录不会被覆盖。

它还会在 `~/.termux/termux.properties` 中启用 Termux 的外部命令开关（保留 `.bak-pig` 备份），并运行 `termux-reload-settings`。**打开一次 Termux App** 让该属性生效，授予 Pig App `com.termux.permission.RUN_COMMAND` 权限，然后按重试。

最后它用 RPC 模式启动一次 `pig` 并请求扩展列表：工具链装不上、扩展编译不出来时会当场报出来，而不是过一阵变成「App 里只有内置命令」。

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

## 故障排查

| 症状 | 修复 |
| --- | --- |
| `pig-relay: not found` | 重新运行安装脚本；它必须位于 `$PREFIX/bin`。 |
| 12 秒后连接被拒绝 | relay 未运行，或 Termux 拒绝了 RUN_COMMAND。检查 `allow-external-apps` 并打开一次 Termux。 |
| `cannot start pig` | `pig` 缺失或不可执行：`command -v pig`，然后 `pig --version`。 |
| `pkg: command not found` | 你不在 Termux 中，或 Termux 太旧。从 F-Droid 重新安装。 |
| `curl: not found` | `pkg install -y curl`。 |
| 重装后残留的 relay / token | `pkill -x pig-relay`，然后按重试。如果 relay 从未有过客户端，它会在 token 错误时退出。 |
| App 不显示模型/窗口 | 检查 `ps -A | grep pig`；重启 App。 |
