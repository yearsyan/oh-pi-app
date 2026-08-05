# API 与 WebSocket 协议

pi2ws 使用 HTTP API 管理持久化 session 和浏览远程文件，使用 WebSocket 收发 pi RPC 与实时事件。进程和历史存储原理见[架构与持久化](architecture.md)。

## 鉴权

`GET /healthz` 不需要鉴权。其他 HTTP API 都推荐使用 Bearer 请求头，避免 token 出现在 URL 和访问日志中：

```http
Authorization: Bearer <TOKEN>
```

WebSocket 连接通过 `token` query 参数鉴权。生产环境务必使用 `wss://` 并关闭或脱敏反向代理的 query 日志。

## 会话管理 HTTP API

### 列出 session

```http
GET /api/sessions
Authorization: Bearer <TOKEN>
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
      "running": true,
      "outputting": false
    }
  ]
}
```

`running` 表示网关当前是否持有正在运行的 pi 子进程，不表示是否有 WebSocket 客户端连接。`outputting` 表示该进程当前处于 `agent_start` 到 `agent_settled` 之间；它为 `true` 时 `running` 也一定为 `true`。

### 查询、重命名和删除 session

```http
GET /api/sessions/<SESSION_ID>

PATCH /api/sessions/<SESSION_ID>
Content-Type: application/json

{"name":"新的会话名称"}

DELETE /api/sessions/<SESSION_ID>
```

以上请求均须携带 `Authorization: Bearer <TOKEN>`。

名称会去除首尾空白，不能为空，最长 200 个 Unicode 字符。重命名会写入网关元数据，并同步到正在运行的 pi session；以后恢复停止的 session 时也会重新应用该名称。

`DELETE` 是永久操作：活动中的 pi 子进程和 WebSocket 会先被正常关闭，随后整个 `<data-dir>/sessions/<session-id>` 目录（包括 pi JSONL、历史快照和 replay WAL）都会被删除。成功返回 HTTP 204。

### 查询新会话能力

```http
GET /api/capabilities?work_dir=/path/to/project
Authorization: Bearer <TOKEN>
```

该接口用与正式会话相同的工作目录、环境和 `--pi-arg` 启动一个短生命周期的 `pi --mode rpc --no-session` 探测进程，因此会加载该工作区可用的 provider、模型和扩展，但不会创建或持久化 session。结果按规范化后的工作目录短期缓存。

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
  ],
  "commands": [
    {
      "name": "skill:review",
      "description": "Review changed code",
      "source": "skill"
    }
  ]
}
```

思考强度是模型级能力，客户端应在切换模型时改用该模型自己的 `thinking_levels`。`commands` 来自 pi 的 `get_commands`，包含当前工作区可用的 extension、prompt template 和 skill 指令；调用时在 `name` 前加 `/`。旧版 pi 不支持 `get_commands` 时返回空列表。

## 文件与目录 HTTP API

### 目录浏览（工作区选择）

```http
GET /fs/list?path=<绝对路径>
Authorization: Bearer <TOKEN>
```

该接口只列出目录下的子目录，不含文件，供客户端实现工作区选择器：

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

### 文件浏览（远程文件管理）

`internal/filebrowser` 包提供完整的文件浏览 HTTP API。鉴权方式与 `/fs/list` 相同，所有接口仅支持 GET。

列出目录下的目录和文件，目录在前、按名称排序：

```http
GET /api/files/list?path=<绝对路径>
```

```json
{
  "path": "/path/to/project",
  "parent": "/path/to",
  "truncated": false,
  "entries": [
    { "name": "cmd", "path": "/path/to/project/cmd", "is_dir": true, "size": 96, "mod_time": 1754300000000 },
    { "name": "main.go", "path": "/path/to/project/main.go", "is_dir": false, "size": 1024, "mod_time": 1754300000000 }
  ]
}
```

读取文本文件内容，默认上限 1 MiB，超出时 `truncated: true`；含 NUL 字节的二进制文件返回 415 `binary_file`：

```http
GET /api/files/read?path=<绝对路径>
```

```json
{ "path": "/path/to/main.go", "name": "main.go", "size": 1024, "truncated": false, "content": "package main\n…" }
```

下载原始文件：

```http
GET /api/files/download?path=<绝对路径>
```

响应使用 `application/octet-stream` 和 `Content-Disposition: attachment`，支持 Range；Android 客户端用它下载 APK 后调起安装。

省略 `path` 时，`list` 从网关的 `--work-dir` 开始；`read` 和 `download` 必须显式给出 `path`。路径必须是绝对路径，服务端会做 symlink 解析；不存在的路径返回 404，目录传给 `read` 或 `download` 返回 400。

## WebSocket API

### 创建 session

连接时必须用 `work_dir` 指定工作区：

```text
ws://127.0.0.1:8080/ws?action=create&token=<TOKEN>&work_dir=/path/to/project&model=openai/gpt-5&thinking=high
```

`work_dir` 是 pi 子进程的工作目录，必须是已存在目录的绝对路径。省略 `work_dir` 或路径非法（相对路径、不存在、不是目录）时，在 WebSocket 升级前返回 HTTP 400。

可选的 `model` 必须使用 `provider/model-id` 格式。可选的 `thinking` 为 `off`、`minimal`、`low`、`medium`、`high`、`xhigh` 或 `max`。它们只允许用于 `action=create`，并在 pi 读取第一条 RPC 命令前作为该 session 的初始配置应用。

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

客户端可以立即使用 `session_id`；以后也可以通过 `GET /api/sessions` 发现并恢复 session。`work_dir` 随 session 元数据持久化。

### 连接历史 session

```text
ws://127.0.0.1:8080/ws?action=attach&session_id=<SESSION_ID>&entry_since=<LAST_ENTRY_ID>&replay_base=<STABLE_SEQ>&replay_since=<LAST_REPLAY_SEQ>&replay_cursor=1&token=<TOKEN>
```

`entry_since` 可省略。客户端应把已经完整提交到本地缓存的最后一个 entry ID 放在这里；网关只同步这个 entry 后面的稳定记录。如果游标不存在于当前稳定历史中，`history_begin.reset` 为 `true`，客户端必须丢弃该 session 的旧缓存并从头接收。

`replay_base` 与 `replay_since` 必须成对出现。前者是本地 replay 缓存所基于的稳定历史 `through_seq`，后者是本地已经完整提交的 replay 高水位。仅当 `entry_since`、`replay_base` 都与服务端当前稳定边界完全一致，且 `replay_since` 未超过服务端高水位时，网关才从该序号后继续；否则会安全地从当前稳定历史边界重新回放。因此客户端可以持久化仍在进行中的 turn，重连或重启后只补缺失尾部。

`replay_cursor=1` 表示客户端需要带序号的实时事件。可回放的 live pi 事件会封装为 `pi2ws/live`，使客户端能把每条事件及其 `seq` 原子写入本地 replay 缓存。

attach 按以下顺序发送，最后才发送 `pi2ws/ready`。收到 `ready` 表示历史、活动事件和实时广播之间的无缝切换已经完成，此时客户端才应发送 `get_state` 等 RPC：

1. `pi2ws/history_begin`
2. 零到多个 WebSocket Binary 消息，内容是原始稳定历史 JSONL 字节
3. `pi2ws/history_end`
4. `pi2ws/replay_begin`
5. 零到多个 `pi2ws/replay_event`；超大单事件则是一个 `pi2ws/replay_binary_begin`，随后跟随多个 WebSocket Binary 消息
6. `pi2ws/replay_end`
7. `pi2ws/ready`
8. attach 高水位之后的实时 pi 事件

```json
{"type":"pi2ws","event":"history_begin","reset":false,"entry_id":"entry-45","through_seq":41,"total_bytes":287104}
<WebSocket Binary: raw JSONL bytes>
{"type":"pi2ws","event":"history_end","entry_id":"entry-45","through_seq":41}
{"type":"pi2ws","event":"replay_begin","from_seq":42,"through_seq":45}
{"type":"pi2ws","event":"replay_event","seq":42,"payload":{"type":"agent_start"},"total_bytes":22}
{"type":"pi2ws","event":"replay_binary_begin","seq":43,"total_bytes":734003}
<WebSocket Binary: raw JSON event bytes>
{"type":"pi2ws","event":"replay_end","through_seq":45}
{
  "type": "pi2ws",
  "event": "ready",
  "action": "attach",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07",
  "work_dir": "/path/to/project"
}
```

`history_begin` 后、`history_end` 前的 Binary 消息直接携带原始 JSONL 字节。客户端应按收到顺序追加到 staging 文件；累计值必须等于 `history_begin.total_bytes`，并且只能在收到 `history_end` 后原子提交 staging 文件和新的 `entry_id` 游标。中途断线或长度不符必须回滚。

`history_begin.total_bytes` 是本阶段的 JSONL 总字节数，可与客户端累计写入的 Binary 字节数计算恢复进度。值为零时字段可能省略。

不超过 256 KiB 的 replay 记录通过 `replay_event.payload` 直接携带 JSON 对象。超大单事件先发送 `replay_binary_begin` 元信息，再用最多 256 KiB 的 Binary 消息发送原始 JSON 字节；Binary 消息没有 Base64 封装，也不独立携带序号。客户端按当前 `seq` 顺序写入临时文件，累计达到 `total_bytes` 后立即完成该事件并解析 JSON；超出声明长度、在完成前收到其他文本消息或在 `replay_end` 时仍不完整都属于协议错误。结合 `replay_begin.from_seq` / `through_seq`，客户端可显示事件级和大事件字节级进度。

启用 `replay_cursor=1` 后，`ready` 之后的可回放事件格式如下。网关会删除 `message_update` 中不影响重建结果的累计 `message` 快照，并且除 `start` 外删除重复的 `partial`；`turn_end` 中与 `message_end` 相同的完整消息也会被删除：

```json
{"type":"pi2ws","event":"live","seq":46,"payload":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"..."}}}
```

每个 Binary 消息最多携带 256 KiB 原始数据。因此稳定历史总量、单个稳定 entry 的大小、活动 turn 回放总量都不会被一个 WebSocket 消息截断。活动事件先以紧凑形式落到磁盘 WAL，attach 从磁盘持续追平；网关在同一序列化临界区内发送 `replay_end`、注册实时高水位，保证不会漏掉 replay 与 live 之间的事件。

replay 序号是单调高水位，不要求在磁盘中连续。网关在 assistant `message_end` 后只保留最终消息，在 `tool_execution_end` 后删除中间工具输出，并对 `queue_update`、会话名等状态采用 last-write-wins。被合并掉的序号不会造成缺口：带较旧 `replay_since` 的客户端会收到其后仍有效的最终状态，再由 `replay_end.through_seq` 提交新的高水位。启动恢复会用相同规则原子迁移旧 WAL。

历史 session 必须由当前 `--data-dir` 对应的 pi2ws 实例创建。不存在或格式非法的 ID 在 WebSocket 升级前返回 HTTP 404。

### 收发 pi RPC

收到 `ready` 后，每个 WebSocket 文本帧发送一个 pi RPC JSON 对象：

```json
{"id":"browser-a-1","type":"prompt","message":"请检查当前项目"}
```

网关把 pi 的响应和流式事件广播给这个 session 上的每一个 WebSocket：

```json
{"id":"browser-a-1","type":"response","command":"prompt","success":true}
```

对于带字符串 `id` 的 `prompt`、`steer` 和 `follow_up` 命令，网关会把该 ID 关联到随后由 pi 发出的 user `message_start` 与 `message_end`，并在事件顶层增加 `source_id`：

```json
{"type":"message_start","source_id":"browser-a-1","message":{"role":"user","content":"请检查当前项目"}}
```

关联后的事件会先写入 replay WAL 再广播，因此活动 turn 重连回放时仍保留同一个 `source_id`。命令没有字符串 `id` 时保持兼容，事件不会增加 `source_id`。客户端应使用不可复用的 ID，并以收到匹配 `source_id` 的 user 事件作为输入已进入 session 的确认。

skill、prompt template 会展开输入文本，extension 指令也可能不产生 user 事件。此类 slash prompt 可额外发送 `"pi2ws_confirm_on_response":true`；网关会在转发给 pi 前移除该字段，并在成功的 prompt response 到达时释放关联状态。客户端应以该 response 作为输入确认。

除上述 user 事件关联字段外，pi 的 `agent_start`、`message_update`、`tool_execution_*`、`agent_end` 等事件保持原协议。完整命令和事件格式以本机 pi 的 `docs/rpc.md` 为准。

标题 extension 或手工重命名成功时，pi 会发送原生事件；客户端应使用它更新当前标题和 session 列表，而不是依赖 `set_session_name` response 中不存在的名称字段：

```json
{"type":"session_info_changed","name":"排查登录回调"}
```

注意：

- 输出是 session 级广播，不是按发送者私有路由。多个客户端必须给会产生用户消息的 RPC `id` 加各自的唯一前缀，避免 `source_id` 冲突。
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
