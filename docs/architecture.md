# 架构与持久化

本文说明 pi2ws 的进程模型、session 生命周期以及历史和实时事件如何保持一致。接口格式见 [API 与 WebSocket 协议](api.md)，运行参数见[部署与配置](deployment.md)。

## 进程模型

一个 pi2ws 网关进程管理多个相互独立的 pi RPC 子进程。每个 session 同一时间最多有一个 pi 子进程，但可以有多个 WebSocket 客户端共享它：

```text
WebSocket A ─┐
WebSocket B ─┼─ session 1 ── pi --mode rpc
WebSocket C ─┘

WebSocket D ─── session 2 ── pi --mode rpc
```

`create` 创建持久化 session 并启动子进程。`attach` 连接已有 session：子进程仍在运行时直接复用，否则从磁盘恢复并启动新进程。

每个 WebSocket 文本帧对应一条 pi RPC JSON；pi stdout 的每条严格 LF JSONL 记录对应一个 WebSocket 文本帧。同一 session 的输出广播给所有已连接客户端，而不是只路由给命令发送者。

## Session 目录

每个 session 使用独立目录：

```text
<data-dir>/sessions/<session-id>/
├── pi2ws-session.json
├── pi2ws-history.json
├── pi2ws-replay.log
└── <pi 创建的 session JSONL 文件>
```

pi2ws 以以下受控参数启动子进程：

```text
pi <额外参数> --mode rpc --session-dir <目录> --session-id <ID>
```

各文件的职责如下：

- `pi2ws-session.json` 是会话列表元数据的权威来源，包含名称、工作区、创建时间和最近活跃时间；旧版元数据会兼容读取。
- pi 创建的 append-only session JSONL 保存已经稳定的历史 entry。
- `pi2ws-replay.log` 是当前活动 turn 的事件 WAL。pi2ws 在广播可回放事件前先将其追加到该文件。
- `pi2ws-history.json` 记录 session JSONL 的稳定文件边界、最后 entry ID 和事件序号。

## 会话标题

标题生成采用 extension 与网关分工：随二进制内嵌的 pi extension 在首个 `before_agent_start` 收到用户请求后立即并行调用配置的轻量模型，再通过 pi 原生 `setSessionName()` 产生 `session_info_changed`；模型调用不阻塞主 agent。网关接收该事件、写入 `pi2ws-session.json` 并原样广播给客户端。客户端先用第一条用户消息显示不持久化的临时标题，再监听该事件替换为正式标题。

网关元数据是最终权威来源。首个有效的 pi 标题只会填充尚未命名的 session；HTTP 或 WebSocket 手工重命名一旦写入，之后到达的冲突生成结果会被丢弃。这样即使手工重命名和异步模型调用并发，手工名称也不会被覆盖。

## 历史与实时事件衔接

attach 分为稳定历史、活动 turn 回放和实时广播三个阶段：

1. 从 pi 的 append-only session JSONL 读取 `entry_since` 之后的稳定 entry。
2. 从磁盘 replay WAL 读取稳定历史高水位之后的活动事件。
3. 在同一序列化临界区内结束 replay 并注册实时高水位，随后转入 live 广播。

稳定历史和 replay 都以分块方式传输，不需要把完整历史读入内存。正在 attach 时会延迟压缩 WAL，避免读者丢失日志尾部。

`agent_settled` 后，pi2ws 会在 `pi2ws-history.json` 中原子记录新的稳定边界，并压缩已经稳定的 replay 日志。详细消息顺序和 chunk 格式见 [attach 历史 session](api.md#连接历史-session)。

## 输入关联与共享控制

对于带字符串 `id` 的 `prompt`、`steer` 和 `follow_up`，网关把命令 ID 关联到随后出现的 user `message_start` 与 `message_end`，并向事件增加 `source_id`。关联后的事件先写入 replay WAL 再广播，因此重连回放仍保留同一个来源 ID。

session 是共享控制域：

- 所有客户端都会看到彼此的输入、响应和工具事件。
- `abort`、`steer` 等命令影响整个 session。
- `new_session`、`switch_session`、`fork` 和 `clone` 会破坏网关的 session 与子进程映射，因此由网关拒绝。
- 多个客户端必须为会产生用户消息的 RPC `id` 使用各自的唯一前缀，避免 `source_id` 冲突。

## 生命周期与关闭

- 所有 WebSocket 都断开后，pi 仍可继续运行。
- session 在 `agent_settled` 后连续 5 分钟没有新 RPC 输入时会被优雅关闭；该时长可通过 `--session-idle-timeout` 调整。
- 空闲回收会以正常关闭码断开仍连接的 WebSocket，原因是 `pi session idle timeout`。session 文件全部保留，下一次 attach 会启动新的 pi 子进程。
- pi 异常退出时，该 session 的 WebSocket 以 1011 关闭；下一次 attach 会启动新进程并恢复持久化会话。
- pi2ws 收到 `SIGINT` 或 `SIGTERM` 后，先关闭 WebSocket 和子进程 stdin，超时后强制结束仍未退出的子进程。
- 永久删除 session 时，活动子进程和 WebSocket 会先正常关闭，随后删除整个 session 目录。
- `PI2WS_TOKEN` 不会传入 pi 子进程环境。
