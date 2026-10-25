[English](install.md) · **简体中文**

# 在 Termux 中安装后端

Android App 是前端。`pig` 和 `pig-relay` 运行在 Termux 里。

## 一条命令

从 F-Droid 安装 Termux（Play Store 版本已过时），打开一次让 `pkg` 完成初始化，然后：

```bash
pkg install -y curl
curl -fsSL https://github.com/linuxwff789/pig-android/releases/latest/download/install-termux.sh | sh
```

`pkg install -y curl` 很关键：全新的 Termux 可能还没有 `curl`，而本安装脚本和官方 PiG 安装脚本都需要它。

它做三件事：

1. 缺少 `pig` 时，用官方 `https://pi-in-go.dev/install.sh` 安装；
2. 从最新发布下载 `pig-relay-android-arm64` 到 `$PREFIX/bin/pig-relay`；
3. 创建 `~/.pig/android-ui/{theme,layout,windows}`。

它还会在 `~/.termux/termux.properties` 中启用 Termux 的外部命令开关（保留 `.bak-pig` 备份），并运行 `termux-reload-settings`。**打开一次 Termux App** 让该属性生效，授予 Pig App `com.termux.permission.RUN_COMMAND` 权限，然后按重试。

### 为什么不直接用 App 内的按钮？

`allow-external-apps=false` 时 `RUN_COMMAND` 本身会被拦截。在干净的 Termux 上，用户不打开该开关，App 就无法运行安装脚本，因此设置对话框会给出两行命令供你粘贴到 Termux。开关打开并且 Termux 被打开过一次后，一键安装即可使用。

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
