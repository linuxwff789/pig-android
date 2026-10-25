[English](README.md) · **简体中文**

# plan — 可选的 plan-mode 扩展

PiG 的只读规划模式。模型先做调查、写出编号计划，在你放行之前不能改动任何东西；
之后它按步骤推进并逐个打勾。

本包是**可选**的。Android App 不依赖它：任何发出
[计划组件契约](../../docs/plan-widget.zh-CN.md) 的扩展都会得到同样的原生卡片，
因此你可以用这个、用 Pi `plan-mode` 的移植版，或用你自己的。

## 安装

```bash
git clone https://github.com/linuxwff789/pig-android
pig install ./pig-android/extensions/plan
```

重启正在运行的 agent，命令才会出现。在 Android App 里：
**设置 → 会话行为 → 重启 pig**（它会等正在跑的回合结束）。
只做 `/reload-runtime` 不够：它重建的是启动时已解析的扩展，不会拾取之后加入
`settings.json` 的包（已对当前 PiG 验证）。

卸载：`pig install --help` 里没有可移除包的选项，所以要编辑
`~/.pig/agent/settings.json` 删掉 `extensions/plan` 条目，或者保留安装、只是
从不运行 `/plan`。

## 命令

| 命令 | 作用 |
| --- | --- |
| `/plan` | 切换 plan-mode |
| `/plan on` / `/plan off` | 显式开启 / 关闭 |
| `/plan go` | 退出 plan-mode，恢复工具，开始执行步骤 |
| `/plan show` (`/todos`) | 打印清单和进度 |
| `/plan add <text>` | 追加一个步骤 |
| `/plan done <n\|text>` / `/plan undo <n\|text>` | 打勾 / 取消打勾 |
| `/plan rm <n>` | 删除一个步骤 |
| `/plan clear` | 丢弃清单 |

`--plan` 会让一次运行以 plan-mode 启动。

## plan-mode 做了什么

- 从当前生效的工具集中移除 `edit` / `write` / `patch` / `multi_edit` ……。
- 在 `tool_call` 里再次拦截这些工具，这样之后才注册的工具也混不进来。
- 用只读策略（`policy.go`）过滤 `bash`：管道里的每条命令都必须以只读动词开头，
  不允许重定向，不允许 `git commit`，不允许 `pkg install`，不允许 `sed -i`、
  `find -delete` ……。`cd dir && rg …` 这种写法是允许的。
- 注入一个 `plan_mode` 系统提示词小节（模式关闭时移除，因此不会残留）。
- 当模型回复里出现 `Plan:` 小节时，提取编号步骤并弹出对话框：
  **执行计划 / 留在计划模式 / 细化计划**。
- 执行期间模型用 `[DONE:n]` 标记进度；`turn_end` 会把这些标记变成已勾选的步骤。
  所有步骤完成后模式结束，但卡片保留，方便回看做了什么。

## 状态存放位置

每次变更都以 `plan-state` 自定义条目的形式追加到会话，因此 App 重启 pig（改上下文
窗口时就会发生）以及切换会话后都还在。另一个会话从空计划开始。

计划**不**存在对话记录里，因此不会通过消息消耗 token；只有在 plan-mode 或执行期间，
它才通过提示词小节送达模型。

## 显示

widget 是遵循 [docs/plan-widget.zh-CN.md](../../docs/plan-widget.zh-CN.md) 的一行 JSON：

```json
{"plan":{"steps":[{"n":1,"text":"Read agents.go","done":true}]}}
```

外加一个带键的状态小标签（`⏸ plan` 或 `📋 2/5`）。App 把它渲染成清单卡片；
不认识这份契约的终端会显示 JSON 文本，这也是本扩展不依赖 `ui.custom` 的原因。
