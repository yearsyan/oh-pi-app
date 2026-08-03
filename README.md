# pi2ws

`pi2ws` 是用 Go 实现的 [pi](https://github.com/badlogic/pi-mono) 网关。HTTP API 负责持久化会话的发现与管理，WebSocket 负责 pi RPC 和实时事件。一个网关进程可以管理多个互相独立的 `pi --mode rpc` 子进程；同一个 pi session 可以被多个 WebSocket 客户端同时连接、输入和监听输出。

```text
WebSocket A ─┐
WebSocket B ─┼─ session 1 ── pi --mode rpc
WebSocket C ─┘

WebSocket D ─── session 2 ── pi --mode rpc
```

## 功能

- `create` 创建持久化 session，并启动独立的 pi RPC 子进程。
- `attach` 连接已有 session；子进程仍在运行时直接复用，不在运行时自动恢复。
- HTTP API 提供服务端权威的 session 列表、详情、重命名和永久删除。
- 同一 session 的多个 WebSocket 都可以发送 RPC 命令，并接收该 pi 进程的全部 RPC 响应和流式事件。
- 每个 WebSocket 文本帧对应一条 pi RPC JSON；pi stdout 的每条严格 LF JSONL 记录对应一个 WebSocket 文本帧。
- WebSocket URL token、HTTP Bearer token、Origin 校验、消息大小限制、慢客户端隔离和优雅退出。
- session 元数据和 pi 会话文件持久化，服务重启后仍可 attach。

## 快速开始

要求：

- Go 1.26.2 或兼容版本
- `pi` 在 `PATH` 中，且支持 `pi --mode rpc`
- pi 已配置好模型和认证

构建并启动：

```bash
mkdir -p bin
go build -o bin/pi2ws ./cmd/pi2ws

export PI2WS_TOKEN="$(openssl rand -hex 32)"
./bin/pi2ws \
  --listen 127.0.0.1:8080 \
  --work-dir /path/to/project
```

默认监听 `127.0.0.1:8080`，健康检查为：

```bash
curl http://127.0.0.1:8080/healthz
```

生产环境应在 TLS 反向代理后提供 `wss://`。

## 会话管理 HTTP API

除健康检查外，HTTP API 都需要鉴权。推荐使用请求头，避免 token 出现在普通 HTTP URL 和访问日志中：

```http
Authorization: Bearer <TOKEN>
```

### 列出 session

```http
GET /api/sessions
```

响应按 `last_active` 从新到旧排列；时间戳单位为 Unix 毫秒：

```json
{
  "sessions": [
    {
      "id": "6d2f8177-d1b5-43ce-927f-250666646e07",
      "name": "检查登录流程",
      "work_dir": "/path/to/project",
      "created_at": 1785736800000,
      "last_active": 1785738600000,
      "running": true
    }
  ]
}
```

`running` 表示网关当前是否持有正在运行的 pi 子进程，不表示是否有 WebSocket 客户端连接。

### 查询、重命名和删除 session

```http
GET /api/sessions/<SESSION_ID>

PATCH /api/sessions/<SESSION_ID>
Content-Type: application/json

{"name":"新的会话名称"}

DELETE /api/sessions/<SESSION_ID>
```

名称会去除首尾空白，最长 200 个 Unicode 字符。重命名会写入网关元数据，并同步到正在运行的 pi session；以后恢复停止的 session 时也会重新应用该名称。

`DELETE` 是永久操作：活动中的 pi 子进程和 WebSocket 会先被正常关闭，随后整个 `<data-dir>/sessions/<session-id>` 目录（包括 pi JSONL、历史快照和 replay WAL）都会被删除。成功返回 HTTP 204。

## WebSocket API

### 创建 session

连接（必须带 `work_dir` 指定工作区）：

```text
ws://127.0.0.1:8080/ws?action=create&token=<TOKEN>&work_dir=/path/to/project
```

`work_dir` 是该 session 的工作区（pi 子进程的工作目录），必须是已存在目录的绝对路径。
省略 `work_dir` 或路径非法（相对路径、不存在、不是目录）时在 WebSocket 升级前返回 HTTP 400。

连接成功后的第一条消息由网关发送：

```json
{
  "type": "pi2ws",
  "event": "ready",
  "action": "create",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07",
  "work_dir": "/path/to/project"
}
```

客户端可以立即使用 `session_id`；以后也可以通过 `GET /api/sessions` 发现并恢复 session。`work_dir` 是该 session 实际使用的工作区，随 session 元数据持久化。

### 连接历史 session

```text
ws://127.0.0.1:8080/ws?action=attach&session_id=<SESSION_ID>&token=<TOKEN>
```

成功后的第一条消息同样是 `pi2ws/ready`，其中 `action` 为 `attach`，`history` 为 `true`，`work_dir` 为 session 创建时记录的工作区（旧版本创建的 session 没有记录，回退为网关的 `--work-dir`）。

```json
{
  "type": "pi2ws",
  "event": "ready",
  "action": "attach",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07",
  "work_dir": "/path/to/project",
  "history": true
}
```

随后网关按固定顺序发送：

1. 最近一次 `agent_settled` 后缓存的 `get_entries` 成功响应；
2. `pi2ws/replay_begin`；
3. 当前尚未 checkpoint 的 turn 事件，每条包装为 `pi2ws/replay`；
4. `pi2ws/replay_end`；
5. attach 高水位之后的实时 pi 事件。

```json
{"type":"response","command":"get_entries","success":true,"data":{"entries":[],"leafId":null}}
{"type":"pi2ws","event":"replay_begin","from_seq":42,"through_seq":45}
{"type":"pi2ws","event":"replay","seq":42,"payload":{"type":"agent_start"}}
{"type":"pi2ws","event":"replay","seq":45,"payload":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"Hello"}}}
{"type":"pi2ws","event":"replay_end","through_seq":45}
```

客户端看到 `history: true` 时不应再主动发送首次 `get_entries`；应先用缓存响应重建稳定历史，再按顺序处理各个 `replay.payload`。网关在同一临界区内截取 replay 高水位并注册实时广播，因此 attach 期间的输出不会漏失或重复。

历史 session 必须由当前 `--data-dir` 对应的 pi2ws 实例创建。不存在或格式非法的 ID 在 WebSocket 升级前返回 HTTP 404。

### 目录浏览（工作区选择）

`GET /fs/list?path=<绝对路径>` 列出目录下的子目录（不含文件），供客户端实现工作区文件浏览器；鉴权使用同一个 `Authorization: Bearer <TOKEN>` 请求头：

```json
{
  "path": "/path/to/project",
  "parent": "/path/to",
  "dirs": [
    { "name": "app", "path": "/path/to/project/app" },
    { "name": "cmd", "path": "/path/to/project/cmd" }
  ]
}
```

省略 `path` 时从网关的 `--work-dir` 开始浏览。`path` 必须是绝对路径；不存在的路径返回 404，无权限读取返回 403。

### 收发 pi RPC

收到 `ready` 后，每个 WebSocket 文本帧发送一个 pi RPC JSON 对象：

```json
{"id":"browser-a-1","type":"prompt","message":"请检查当前项目"}
```

网关会把 pi 的响应和所有流式事件原样广播给这个 session 上的每一个 WebSocket：

```json
{"id":"browser-a-1","type":"response","command":"prompt","success":true}
```

pi 的 `agent_start`、`message_update`、`tool_execution_*`、`agent_end` 等事件也保持原协议。完整命令和事件格式以本机 pi 的 `docs/rpc.md` 为准。

注意：

- 输出是 session 级广播，不是按发送者私有路由。多个客户端应给 RPC `id` 加各自的唯一前缀。
- attach 自动发送稳定的 `get_entries` 历史和当前活动 turn 的事件回放。客户端仍可在连接后主动发送 `get_state`、`get_messages`、`get_entries` 或 `get_tree`。
- 普通 pi 事件（包括可能正阻塞 pi 的 `extension_ui_request`）都会进入活动 turn 回放；RPC `response` 不回放，避免 attach 客户端误处理并非由它发起的旧命令响应。回放内存超过 `--max-replay-bytes` 时会发送 `pi2ws/replay_unavailable`，客户端应立即请求一次 `get_entries`，并在下一次 `agent_settled` 后再次同步；磁盘 WAL 不受该内存上限影响。
- `abort`、`steer` 等命令会影响整个共享 session。
- `new_session`、`switch_session`、`fork`、`clone` 会破坏网关的 session 与子进程映射，因此会被网关拒绝。新 session 应通过新的 `action=create` 连接创建。
- 格式错误的命令只会向发送方返回 `pi2ws/error`，不会转发给 pi。

浏览器端最小示例：

```js
const ws = new WebSocket(
  "ws://127.0.0.1:8080/ws?action=create&token=" +
    encodeURIComponent(token) +
    "&work_dir=" + encodeURIComponent("/path/to/project"),
);

ws.onmessage = ({ data }) => {
  const message = JSON.parse(data);
  console.log(message);

  if (message.type === "pi2ws" && message.event === "ready") {
    localStorage.setItem("piSessionId", message.session_id);
    ws.send(JSON.stringify({
      id: crypto.randomUUID(),
      type: "get_state",
    }));
  }
};
```

## 进程与持久化语义

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

- 同一 session 在同一时间最多有一个 pi 子进程。
- `pi2ws-session.json` 是会话列表元数据的权威来源，包含名称、工作区、创建时间和最近活跃时间；旧版元数据会兼容读取。
- pi2ws 会在广播可回放事件前先追加 `pi2ws-replay.log`；`agent_settled` 后原子更新 `pi2ws-history.json` 并压缩 replay 日志。
- 所有 WebSocket 都断开后，pi 仍可继续运行；session 在 `agent_settled` 后连续 5 分钟没有新 RPC 输入时会被优雅关闭。
- 空闲回收会以正常关闭码断开仍连接的 WebSocket，原因是 `pi session idle timeout`。session 文件全部保留，下一次 attach 会启动新的 pi 子进程。
- pi 异常退出时，该 session 的 WebSocket 以 1011 关闭；下一次 attach 会启动新进程并恢复持久化会话。
- pi2ws 收到 `SIGINT` 或 `SIGTERM` 后，先关闭 WebSocket 和子进程 stdin，超时后强制结束仍未退出的子进程。
- `PI2WS_TOKEN` 不会传入 pi 子进程环境。

## 配置

| 参数 | 环境变量 | 默认值 | 说明 |
|---|---|---:|---|
| `--listen` | `PI2WS_LISTEN` | `127.0.0.1:8080` | HTTP 监听地址 |
| `--token` | `PI2WS_TOKEN` | 无 | 必填鉴权 token |
| `--data-dir` | `PI2WS_DATA_DIR` | `~/.local/state/pi2ws` | session 持久化目录 |
| `--work-dir` | `PI2WS_WORK_DIR` | 当前目录 | 旧会话 attach 的回退目录、`/fs/list` 的浏览起点 |
| `--pi` | `PI2WS_PI_COMMAND` | `pi` | pi 可执行文件 |
| `--pi-arg` | 无 | 无 | 额外 pi 参数，可重复 |
| `--allow-origin` | 无 | 同源 | 允许的浏览器 Origin，可重复；`*` 表示全部 |
| `--max-message-bytes` | 无 | `16777216` | 单条 WS 命令和 pi 事件上限 |
| `--max-replay-bytes` | 无 | `67108864` | 每个活动 session 的 turn 回放内存上限；磁盘 WAL 仍持续记录 |
| `--session-idle-timeout` | 无 | `5m` | `agent_settled` 后无新 RPC 输入的 pi 进程回收时间 |
| `--shutdown-timeout` | 无 | `10s` | 优雅退出等待时间 |

额外 pi 参数示例：

```bash
./bin/pi2ws \
  --pi-arg=--provider \
  --pi-arg=openai \
  --pi-arg=--model \
  --pi-arg=gpt-5
```

`--mode`、`--session*`、`--continue`、`--resume`、`--fork` 和 `--no-session` 由网关管理，不能通过 `--pi-arg` 覆盖。

## 安全注意事项

- WebSocket token 会出现在反向代理的请求 URL 中。务必使用 `wss://`，关闭或脱敏 `/ws` query 日志，并定期轮换 token；普通 HTTP API 应使用 Bearer 请求头。
- 默认只允许无 `Origin` 的非浏览器客户端和同源浏览器连接。跨域前端需要显式配置 `--allow-origin=https://app.example.com`。
- 共享同一个 session 的客户端拥有同等控制权，也会看到彼此的输入、模型响应和工具输出。只把同一个 session ID 发给互相信任的客户端。
- 建议网关本身只监听回环或私网地址，由反向代理负责 TLS、限流和外部访问控制。

## 测试

```bash
go test ./...
go test -race ./...
```

测试使用受控的假 pi 子进程，不会调用模型或消耗 API。
