[English](README.md) · **简体中文**

# rtk 扩展（Go 移植）

在命令执行前，把每个 `bash` 工具调用重写成等价的 `rtk` 形式，于是 `cat`、`grep`、
`git diff`、`find` 之类返回的是压缩输出，而不是整个文件。所有重写逻辑都在 `rtk` CLI
（`rtk rewrite`）里，它是 Rust 实现的；这个扩展只是约 150 行的钩子，负责调用它并把新命令
交回宿主。

这是 PiG 自带那个 TypeScript 扩展的移植版本
（`~/.pig/agent/extensions/rtk/index.ts`，最初与 Oh My Pi 共用）。移植只有一个原因：
当只剩这一个 TypeScript 扩展时，PiG 需要一整块 Node.js 运行时进程单元（≈62 MB RSS）
仅仅为了跑一个 30 行的钩子。这套配置里其他部分本来就是 Go，所以这次移植把 Node 彻底
从图景中移除。

## 要求

- `PATH` 中有 `rtk >= 0.23.0`（`rtk rewrite` 出现在 0.23.0）。
  在 Termux 上，入口是一个很小的 `sh` 包装脚本，它通过
  `$PREFIX/glibc/lib/ld-linux-aarch64.so.1` 启动链接 glibc 的 Rust 二进制，
  因为上游只发布 `*-unknown-linux-gnu`。
- 不需要 Node.js。

## 行为

与 TypeScript 原版一致：

| 规则 | 效果 |
| --- | --- |
| 工具名 | 只考虑 `bash` 调用 |
| `command` | 必须是非空字符串 |
| 已重写 | 以 `rtk ` 开头的命令直接通过 |
| `RTK_DISABLED=1` | 不重写 |
| `rtk --version` | 每个会话探测一次；缺失或 `< 0.23.0` 时禁用重写，并在状态栏显示 `RTK disabled: …` |
| `rtk rewrite` | 超时 2 s；出错、超时或退出码不是 0/3 时原样通过 |
| 退出码 0 / 3 | 重写 / 提示性重写 —— 两者都会替换 `input.command` |
| 任何意外情况 | 失败开放：执行原始命令 |

宿主会应用被修改过的 `input`（Go SDK 以 `_pigToolCallInput` 传回），因此真正执行的是
重写后的命令 —— 尽管会话记录里保存的是模型提出的那条命令。

## 安装

```bash
pig install ~/dev/pig-android/extensions/rtk      # adds it to settings.packages
```

或者手工把路径加到 `~/.pig/agent/settings.json` 的 `packages` 中：

```json
"../../dev/pig-android/extensions/rtk"
```

## 调试

`rtk rewrite "<cmd>"` 是唯一事实来源；直接运行它即可看到某条命令会被变成什么：

```bash
$ rtk rewrite "cat big.txt"; echo "rc=$?"
rtk read big.txt
rc=3
```
