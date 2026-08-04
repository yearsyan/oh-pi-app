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

### macOS LaunchAgent 部署

仓库提供可重复执行的生产部署脚本。首次执行时传入 token；脚本会构建去除本地路径和调试符号的二进制、安装到 `~/.local/bin/pi2ws`，然后创建并启动当前用户的 LaunchAgent：

```bash
PI2WS_TOKEN="$(openssl rand -hex 32)" ./scripts/deploy-launchd.sh
```

之后更新代码时直接重复执行即可；现有 token 和部署配置会被保留：

```bash
./scripts/deploy-launchd.sh
```

首次部署或需要修改配置时，可通过环境变量覆盖默认值：

```bash
PI2WS_TOKEN="<TOKEN>" \
PI2WS_LISTEN="0.0.0.0:18080" \
PI2WS_DATA_DIR="$HOME/.local/state/pi2ws" \
PI2WS_WORK_DIR="/path/to/project" \
PI2WS_PI_COMMAND="/absolute/path/to/pi" \
./scripts/deploy-launchd.sh
```

部署文件及运行状态：

- 二进制：`~/.local/bin/pi2ws`
- LaunchAgent：`~/Library/LaunchAgents/io.github.yearsyan.pi2ws.plist`
- token：`~/.config/pi2ws/token`（权限 `0600`，不会写入 plist 或命令行）
- session 与日志：`~/.local/state/pi2ws/`

```bash
launchctl print "gui/$(id -u)/io.github.yearsyan.pi2ws"
curl http://127.0.0.1:8080/healthz
```

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

### 查询新会话能力

```http
GET /api/capabilities?work_dir=/path/to/project
Authorization: Bearer <TOKEN>
```

该接口用与正式会话相同的工作目录、环境和 `--pi-arg` 启动一个短生命周期的
`pi --mode rpc --no-session` 探测进程，因此会加载该工作区可用的 provider、模型和扩展，但不会创建或持久化 session。结果按规范化后的工作目录短期缓存。

```json
{
  "work_dir": "/path/to/project",
  "default": {
    "provider": "openai",
    "model_id": "gpt-5",
    "thinking_level": "medium"
  },
  "models": [
    {
      "id": "gpt-5",
      "name": "GPT-5",
      "provider": "openai",
      "thinking_levels": ["off", "minimal", "low", "medium", "high"]
    }
  ]
}
```

思考强度是模型级能力，客户端应在切换模型时改用该模型自己的 `thinking_levels`。

## WebSocket API

### 创建 session

连接（必须带 `work_dir` 指定工作区）：

```text
ws://127.0.0.1:8080/ws?action=create&token=<TOKEN>&work_dir=/path/to/project&model=openai/gpt-5&thinking=high
```

`work_dir` 是该 session 的工作区（pi 子进程的工作目录），必须是已存在目录的绝对路径。
省略 `work_dir` 或路径非法（相对路径、不存在、不是目录）时在 WebSocket 升级前返回 HTTP 400。
可选的 `model` 必须使用 `provider/model-id` 格式；可选的 `thinking` 为
`off`、`minimal`、`low`、`medium`、`high`、`xhigh` 或 `max`。它们只允许用于
`action=create`，并在 pi 读取第一条 RPC 命令前作为该 session 的初始配置应用。

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
ws://127.0.0.1:8080/ws?action=attach&session_id=<SESSION_ID>&entry_since=<LAST_ENTRY_ID>&token=<TOKEN>
```

`entry_since` 可省略。客户端应把已经完整提交到本地缓存的最后一个 entry ID 放在这里；网关只同步这个 entry 后面的稳定记录。如果游标不存在于当前稳定历史中，`history_begin.reset` 为 `true`，客户端必须丢弃该 session 的旧缓存并从头接收。

attach 按以下顺序发送，最后才发送 `pi2ws/ready`。收到 `ready` 表示历史、活动事件和实时广播之间的无缝切换已经完成，此时客户端才应发送 `get_state` 等 RPC：

1. `pi2ws/history_begin`；
2. 零到多个 `pi2ws/history_chunk`；
3. `pi2ws/history_end`；
4. `pi2ws/replay_begin`；
5. 零到多个 `pi2ws/replay_chunk`；
6. `pi2ws/replay_end`；
7. `pi2ws/ready`；
8. attach 高水位之后的实时 pi 事件。

```json
{"type":"pi2ws","event":"history_begin","reset":false,"entry_id":"entry-45","through_seq":41}
{"type":"pi2ws","event":"history_chunk","data":"eyJ0eXBlIjoibWVzc2FnZSIsImlkIjoiZW50cnktNDUifQo="}
{"type":"pi2ws","event":"history_end","entry_id":"entry-45","through_seq":41}
{"type":"pi2ws","event":"replay_begin","from_seq":42,"through_seq":45}
{"type":"pi2ws","event":"replay_chunk","seq":42,"data":"eyJ0eXBlIjoiYWdlbnRfc3RhcnQifQ==","final":true}
{"type":"pi2ws","event":"replay_end","through_seq":45}
{
  "type": "pi2ws",
  "event": "ready",
  "action": "attach",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07",
  "work_dir": "/path/to/project"
}
```

`history_chunk.data` 是 Base64 编码的原始 JSONL 字节；把所有 chunk 解码后按顺序追加到 staging 文件，得到的是 `entry_since` 之后的完整 entry 行。客户端只能在收到 `history_end` 后原子提交 staging 文件和新的 `entry_id` 游标；中途断线必须回滚。`replay_chunk.data` 同样是 Base64 字节，同一个 `seq` 的 chunk 拼成一条原始 pi JSON 事件，`final: true` 表示该事件结束。

每个 chunk 最多携带 256 KiB 原始数据。因此稳定历史总量、单个稳定 entry 的大小、活动 turn 回放总量都不会再被一个 WebSocket 帧或旧的 64 MiB 内存回放上限截断。活动事件先落到磁盘 WAL，attach 从磁盘持续追平；网关在同一序列化临界区内发送 `replay_end`、注册实时高水位，保证不会漏掉 replay 与 live 之间的事件。

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
- attach 从 pi 的 append-only session JSONL 按 entry 游标发送稳定历史，并从磁盘 WAL 发送当前活动 turn；不再调用或内嵌整包 `get_entries`。客户端仍可在 `ready` 后主动发送其他 pi RPC。
- 普通 pi 事件（包括可能正阻塞 pi 的 `extension_ui_request`）都会进入活动 turn WAL；RPC `response` 不回放，避免 attach 客户端误处理并非由它发起的旧命令响应。
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
- pi2ws 会在广播可回放事件前先追加 `pi2ws-replay.log`；`agent_settled` 后在 `pi2ws-history.json` 中原子记录 session JSONL 的稳定文件边界、最后 entry ID 和事件序号，并压缩已经稳定的 replay 日志。正在 attach 时延迟压缩，避免读者丢失 WAL 尾部。
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
| `--max-message-bytes` | 无 | `134217728` | 单条 WS 命令和 pi 事件上限 |
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
