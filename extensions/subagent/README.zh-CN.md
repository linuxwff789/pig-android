[English](README.md) · **简体中文**

# subagent 扩展（Go）

一个 `subagent` 工具：模型把一件自包含的任务委派给拥有自己上下文窗口的**子 agent
会话**，并拿回子 agent 的答案。

移植自 [`pi-subagent-feather`](https://www.npmjs.com/package/pi-subagent-feather)
（MIT，本身派生自 `@eggmasonvalue/pi-subagent` 2.0.1）的 `subagent` 工具：相同的参数、
相同的回执结构、相同的「并行调用彼此独立」约定。执行模型是 PiG 的，而不是 pi 的：

| | pi-subagent-feather | 本移植 |
| --- | --- | --- |
| 子 agent 运行时 | 再启动一个 `pi` 进程 | `ctx.CreateAgentSession`（进程内，宿主侧） |
| 开销 | +1 个进程（手机上约 60–250 MB） | +0 个进程，共用 provider 运行时 |
| 子 agent 记录 | 父会话旁边的一个 JSONL 文件 | 内存中（`InMemory: true`），不落盘 |
| `resume` | 支持（子会话路径） | 不支持 —— 没有任何东西被持久化以便恢复 |

## 工具

```
subagent(
  task      string   required   everything the child needs; it cannot see this conversation
  label     string   optional   short name shown in the receipt (default "child")
  model     string   optional   "provider/id" or plain id; default inherits the session model
  thinking  string   optional   off|minimal|low|medium|high|xhigh|max
  tools     []string optional   allowlist, e.g. ["read","bash"] for read-only scouting;
                                omit = inherit all except `subagent`; [] = no tools
  cwd       string   optional   child working directory (default: the session's)
  timeoutMs int      optional   stop after N ms (default 10 min, max 30 min), returns the partial answer
)
```

结果：

```
done · scout-auth · 2 turns · ↑13707 ↓74 tok · 1 tools · 4s
<child's final answer, truncated to 12000 chars with head+tail kept>
```

状态为 `done`、`timeout`、`failed` 或 `empty`。

## 委派规则

- 子 agent 永不继承 `subagent`，因此委派不会意外分叉。
  在 `tools` 中显式点名即视为选择启用（嵌套是有意允许的）。
- 除非 `model` / `thinking` / `cwd` 另有指定，子 agent 继承会话的模型、思考档位、
  资源和上下文文件。
- 子 agent 的第一个提示词会带上完整的 system prompt，所以即使任务很小，一次委派也要花
  约 10–15k 输入 token；父 agent 只为回执和答案付费。这正是重点：*调查*留在子 agent 的
  上下文里，而不是这个上下文里。
- 并行：一轮中的多个 `subagent` 调用会作为独立的子会话并行运行。

## 配置

| 环境变量 | 效果 |
| --- | --- |
| `PIG_SUBAGENT=off` | 工具仍保持注册，但每次调用都返回 "disabled" |

## 安装

```bash
pig install ~/dev/pig-android/extensions/subagent
```

## 备注

- 会运行 `ctx.BindExtensions()`，以便子 agent 的资源能在无头模式下启动。
- 回执中的 token 是子 agent 可计费的输入/输出，由其 assistant 消息累加得到；
  子 agent 的缓存读取不会被扣除。
- `PIG_SUBAGENT=off` 在每次调用时读取，所以切换它不需要 `/reload`。
