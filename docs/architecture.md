# 架构与持久化

本文说明 ohpi-gateway 的进程模型、session 生命周期以及历史和实时事件如何保持一致。接口格式见 [API 与 WebSocket 协议](api.md)，运行参数见[部署与配置](deployment.md)。

## 进程模型

一个 ohpi-gateway 进程管理多个相互独立的 pi RPC 子进程。每个 session 同一时间最多有一个 pi 子进程，但可以有多个 WebSocket 客户端共享它：

```text
WebSocket A ─┐
WebSocket B ─┼─ session 1 ── pi --mode rpc
WebSocket C ─┘

WebSocket D ─── session 2 ── pi --mode rpc
```

`create` 创建持久化 session 并启动子进程。`attach` 连接已有 session：子进程仍在运行时直接复用，否则从磁盘恢复并启动新进程。

每个 WebSocket 文本帧对应一条 pi RPC JSON；pi stdout 的每条严格 LF JSONL 记录对应一个 WebSocket 文本帧。同一 session 的输出广播给所有已连接客户端，而不是只路由给命令发送者。

Provider 列表和认证操作使用独立的短生命周期 pi RPC 辅助进程。它禁用工作区扩展、
skill、prompt template、上下文和工具，只显式加载网关内嵌的认证桥接 extension；因此
第三方 extension 不能介入凭据提示。桥接层调用 pi 自身的 Provider runtime，凭据仍由
pi 通过带跨进程锁的 `auth.json` 存储实现读写，网关和 App 都不自行持久化 Provider 密钥。
管理操作串行执行，登录连接断开时辅助进程立即退出。

管理清单以 pi 的内置 Provider catalog 为边界，不包含 `models.json` 新增的自定义
Provider 或 extension Provider。正式 session 和工作空间 `capabilities` 接口仍按原方式加载完整
工作区配置，所以这些自定义来源提供的模型不会从模型选择器中消失。认证变化后能力缓存
会失效；已稳定的 session 进程会回收并在正常重连时载入新凭据，正在生成的调用不会被中断。

## 工作空间与 Session 目录

工作空间是服务端权威对象。当前一个工作空间对应一个经过 symlink 解析的绝对目录；同一目录只注册一次。工作空间元信息独立持久化：

```text
<data-dir>/workspaces/<workspace-id>/workspace.json
```

其中包含 ID、目录、显示名、追加系统提示词和时间戳。技术栈不持久化，而是在返回 API 时根据根目录的框架配置、语言 manifest 和 `package.json` 依赖按优先级重新探测。未来可以在该结构上增加子工作空间；协议 2 暂不提供子目录层级。

每个 session 使用独立目录：

```text
<data-dir>/sessions/<session-id>/
├── ohpi-session.json
├── ohpi-history.json
├── ohpi-replay.log
├── ohpi-metrics.jsonl
└── <pi 创建的 session JSONL 文件>
```

ohpi 以以下受控参数启动子进程：

```text
pi <额外参数> [--append-system-prompt <工作空间提示词>] --mode rpc --session-dir <目录> --session-id <ID>
```

各文件的职责如下：

- `ohpi-session.json` 是会话元数据的权威来源，包含名称、`workspace_id`、创建时间和最近活跃时间。网关启动时会把旧格式中直接保存目录的 session 一次性归入对应工作空间；HTTP 与 WebSocket 协议本身不兼容旧接口。
- pi 创建的 append-only session JSONL 保存已经稳定的历史 entry。
- `ohpi-replay.log` 是当前活动 turn 的紧凑事件 WAL。ohpi 在广播可回放事件前先写入该文件；累计 message 快照会被剥离，已完成消息/工具的中间更新会被最终状态替代。
- `ohpi-history.json` 记录 session JSONL 的稳定文件边界、最后 entry ID 和事件序号。
- `ohpi-metrics.jsonl` 每次追加一条可测量 assistant 模型调用的 TTFT、生成时长和 output token。它独立于对话历史与 replay，可在 pi 子进程停止后继续查询。

## 生成性能统计

网关在同一 pi RPC stdout 流中关联 `turn_start`、首个有效 assistant 流式输出与最终 `message_end`。每次成功调用结束后同步追加一个小型 JSONL 样本；写入失败只记录警告，不中断模型会话。TTFT 是网关观测的端到端等待时间，TPS 则在最终 provider usage 可用后结算。HTTP 查询按总 output token 和总生成时长计算加权平均，并对 TTFT 求调用平均值。

性能样本不作为 WebSocket 事件广播，也不进入 `ohpi-replay.log`。App 在刷新 pi 原生 `get_session_stats` 时并行查询 session metrics，因此重连无需依赖性能事件回放。

## 会话标题

标题生成采用 extension 与网关分工：随二进制内嵌的 pi extension 在首个 `before_agent_start` 收到用户请求后立即并行调用配置的轻量模型，再通过 pi 原生 `setSessionName()` 产生 `session_info_changed`；模型调用不阻塞主 agent。网关接收该事件、写入 `ohpi-session.json` 并原样广播给客户端。客户端先用第一条用户消息显示不持久化的临时标题，再监听该事件替换为正式标题。

网关元数据是最终权威来源。首个有效的 pi 标题只会填充尚未命名的 session；HTTP 或 WebSocket 手工重命名一旦写入，之后到达的冲突生成结果会被丢弃。这样即使手工重命名和异步模型调用并发，手工名称也不会被覆盖。

## 历史与实时事件衔接

attach 分为稳定历史、活动 turn 回放和实时广播三个阶段：

1. 从 pi 的 append-only session JSONL 读取 `entry_since` 之后的稳定 entry。
2. 当客户端的稳定基线完全匹配时，从 `replay_since` 之后读取活动事件；否则从磁盘 replay WAL 的稳定历史高水位之后读取。
3. 在同一序列化临界区内结束 replay 并注册实时高水位，随后转入 live 广播。

稳定历史直接以 WebSocket Binary 消息传输原始 JSONL 字节；普通 replay 记录作为嵌套 JSON 对象发送，超过 256 KiB 的单事件在文本元信息后以 Binary 消息传输原始 JSON 字节。两条路径都没有同步层 Base64。App 在接收协程中施加背压并顺序写入 staging 文件，以声明的原始字节数提交或回滚。正在 attach 时会延迟压缩 WAL，避免读者丢失日志尾部。

支持 replay 游标的客户端会把活动事件与其网关序号一起持久化，并在 live 阶段接收带 `seq` 的 `ohpi/live` 封装。稳定 entry 游标、稳定序号基线和 replay 高水位三者必须同时匹配才允许跳过旧 WAL；任一不匹配都会回退到安全的稳定边界。这既避免长时间运行的 turn 在每次重连时被完整重传，也不会以流量优化换取历史缺口。

assistant 消息和工具执行结束时，ohpi 会原子合并被最终状态覆盖的流式更新；`agent_settled` 后再在 `ohpi-history.json` 中记录新的稳定边界，并删除已经进入稳定历史的 replay。启动恢复同样会迁移旧格式或未压缩 WAL。详细消息顺序和 chunk 格式见 [attach 历史 session](api.md#连接历史-session)。

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
- ohpi 收到 `SIGINT` 或 `SIGTERM` 后，先关闭 WebSocket 和子进程 stdin，超时后强制结束仍未退出的子进程。
- 永久删除 session 时，活动子进程和 WebSocket 会先正常关闭，随后删除整个 session 目录。
- 手动停止单个 pi 进程时保留整个 session 目录；正在输出的进程必须先收到 `abort` 并到达 `agent_settled`，网关才允许停止。
- `OHPI_TOKEN` 不会传入 pi 子进程环境。
