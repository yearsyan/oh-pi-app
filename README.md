# pi2ws

`pi2ws` 是用 Go 实现的 [pi](https://github.com/badlogic/pi-mono) 网关。HTTP API 负责持久化会话的发现与管理，WebSocket 负责 pi RPC 和实时事件。一个网关进程可以管理多个互相独立的 `pi --mode rpc` 子进程；同一个 pi session 可以被多个 WebSocket 客户端同时连接、输入和监听输出。

```text
WebSocket A ─┐
WebSocket B ─┼─ session 1 ── pi --mode rpc
WebSocket C ─┘

WebSocket D ─── session 2 ── pi --mode rpc
```

## 功能

- 创建、发现、恢复、重命名和永久删除持久化 session。
- 同一 session 支持多个 WebSocket 客户端共同输入并接收实时输出。
- 严格保持 WebSocket 文本帧与 pi RPC JSONL 记录之间的一对一映射。
- 稳定历史与活动 turn WAL 持久化，重连时可无缝衔接 replay 和实时事件。
- 支持工作区能力探测、目录浏览和远程文件读取与下载。
- 首轮结束后由可配置的轻量模型生成会话标题，并同步到所有客户端。
- 提供 token 鉴权、Origin 校验、消息大小限制、慢客户端隔离和优雅退出。

## 快速开始

要求：

- Go 1.26.2 或兼容版本
- `pi` 0.83.0 或更高版本在 `PATH` 中（标题链路使用 `agent_settled` 与 `session_info_changed`）
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

默认监听 `127.0.0.1:8080`。确认服务正常：

```bash
curl http://127.0.0.1:8080/healthz
```

生产环境应在 TLS 反向代理后提供 `wss://`。完整部署方式和配置项见[部署与配置](docs/deployment.md)。

## 文档

- [API 与 WebSocket 协议](docs/api.md)：HTTP 会话管理、文件浏览、WebSocket create/attach、历史同步和 pi RPC。
- [架构与持久化](docs/architecture.md)：进程模型、session 生命周期、稳定历史、replay WAL 和关闭语义。
- [部署与配置](docs/deployment.md)：macOS LaunchAgent、命令行参数、环境变量和安全建议。
- [Kotlin Multiplatform 客户端](app/README.md)：Android、桌面和 iOS 客户端的构建说明。

## 开发与测试

```bash
go test ./...
go test -race ./...
go vet ./...
```

测试使用受控的假 pi 子进程，不会调用模型或消耗 API。涉及 WebSocket、队列、进程或关闭流程的变更应运行 race detector。
