# pi2ws

`pi2ws` 是用 Go 实现的 [pi](https://github.com/badlogic/pi-mono) RPC WebSocket 网关。一个网关进程可以管理多个互相独立的 `pi --mode rpc` 子进程；同一个 pi session 可以被多个 WebSocket 客户端同时连接、输入和监听输出。

```text
WebSocket A ─┐
WebSocket B ─┼─ session 1 ── pi --mode rpc
WebSocket C ─┘

WebSocket D ─── session 2 ── pi --mode rpc
```

## 功能

- `create` 创建持久化 session，并启动独立的 pi RPC 子进程。
- `attach` 连接已有 session；子进程仍在运行时直接复用，不在运行时自动恢复。
- 同一 session 的多个 WebSocket 都可以发送 RPC 命令，并接收该 pi 进程的全部 RPC 响应和流式事件。
- 每个 WebSocket 文本帧对应一条 pi RPC JSON；pi stdout 的每条严格 LF JSONL 记录对应一个 WebSocket 文本帧。
- URL query token 鉴权、Origin 校验、消息大小限制、慢客户端隔离和优雅退出。
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

## WebSocket API

### 创建 session

连接：

```text
ws://127.0.0.1:8080/ws?action=create&token=<TOKEN>
```

连接成功后的第一条消息由网关发送：

```json
{
  "type": "pi2ws",
  "event": "ready",
  "action": "create",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07"
}
```

客户端必须保存 `session_id`，以后用它恢复 session。

### 连接历史 session

```text
ws://127.0.0.1:8080/ws?action=attach&session_id=<SESSION_ID>&token=<TOKEN>
```

成功后的第一条消息同样是 `pi2ws/ready`，其中 `action` 为 `attach`。

历史 session 必须由当前 `--data-dir` 对应的 pi2ws 实例创建。不存在或格式非法的 ID 在 WebSocket 升级前返回 HTTP 404。

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
- attach 不自动回放历史输出。连接后可发送 `get_state`、`get_messages`、`get_entries` 或 `get_tree`。
- `abort`、`steer` 等命令会影响整个共享 session。
- `new_session`、`switch_session`、`fork`、`clone` 会破坏网关的 session 与子进程映射，因此会被网关拒绝。新 session 应通过新的 `action=create` 连接创建。
- 格式错误的命令只会向发送方返回 `pi2ws/error`，不会转发给 pi。

浏览器端最小示例：

```js
const ws = new WebSocket(
  "ws://127.0.0.1:8080/ws?action=create&token=" +
    encodeURIComponent(token),
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
└── <pi 创建的 session JSONL 文件>
```

pi2ws 以以下受控参数启动子进程：

```text
pi <额外参数> --mode rpc --session-dir <目录> --session-id <ID>
```

- 同一 session 在同一时间最多有一个 pi 子进程。
- 所有 WebSocket 都断开后，pi 默认继续运行，以便后续 attach 直接复用。
- pi 异常退出时，该 session 的 WebSocket 以 1011 关闭；下一次 attach 会启动新进程并恢复持久化会话。
- pi2ws 收到 `SIGINT` 或 `SIGTERM` 后，先关闭 WebSocket 和子进程 stdin，超时后强制结束仍未退出的子进程。
- `PI2WS_TOKEN` 不会传入 pi 子进程环境。

## 配置

| 参数 | 环境变量 | 默认值 | 说明 |
|---|---|---:|---|
| `--listen` | `PI2WS_LISTEN` | `127.0.0.1:8080` | HTTP 监听地址 |
| `--token` | `PI2WS_TOKEN` | 无 | 必填鉴权 token |
| `--data-dir` | `PI2WS_DATA_DIR` | `~/.local/state/pi2ws` | session 持久化目录 |
| `--work-dir` | `PI2WS_WORK_DIR` | 当前目录 | 每个 pi 子进程的工作目录 |
| `--pi` | `PI2WS_PI_COMMAND` | `pi` | pi 可执行文件 |
| `--pi-arg` | 无 | 无 | 额外 pi 参数，可重复 |
| `--allow-origin` | 无 | 同源 | 允许的浏览器 Origin，可重复；`*` 表示全部 |
| `--max-message-bytes` | 无 | `16777216` | 单条 WS 命令和 pi 事件上限 |
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

- URL token 会出现在反向代理的请求 URL 中。务必使用 `wss://`，关闭或脱敏 `/ws` query 日志，并定期轮换 token。
- 默认只允许无 `Origin` 的非浏览器客户端和同源浏览器连接。跨域前端需要显式配置 `--allow-origin=https://app.example.com`。
- 共享同一个 session 的客户端拥有同等控制权，也会看到彼此的输入、模型响应和工具输出。只把同一个 session ID 发给互相信任的客户端。
- 建议网关本身只监听回环或私网地址，由反向代理负责 TLS、限流和外部访问控制。

## 测试

```bash
go test ./...
go test -race ./...
```

测试使用受控的假 pi 子进程，不会调用模型或消耗 API。
