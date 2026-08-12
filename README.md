# Oh Pi App

**Oh Pi App**（短名 `ohpi`）是 [pi](https://github.com/badlogic/pi-mono) 的远程网关 + 多端客户端 monorepo。

- **网关** `ohpi-gateway`：Go 实现。HTTP API 负责持久化会话的发现与管理，WebSocket 负责 pi RPC 和实时事件。一个网关进程可以管理多个互相独立的 `pi --mode rpc` 子进程；同一个 pi session 可以被多个 WebSocket 客户端同时连接、输入和监听输出。
- **客户端** `app/`：Kotlin Multiplatform（Android / Desktop / iOS），连接你自建的网关。

```text
WebSocket A ─┐
WebSocket B ─┼─ session 1 ── pi --mode rpc
WebSocket C ─┘

WebSocket D ─── session 2 ── pi --mode rpc
```

## 功能

- 服务端维护工作空间及其元信息，自动探测技术栈，并按工作空间分页发现 session；删除工作空间时保留全部会话，同目录重新注册后自动恢复。
- 创建、恢复、重命名、停止运行进程和永久删除持久化 session；工作空间可为每次 Pi 启动追加系统提示词，并配置多组 Skills/Extensions 路径及是否关闭其他资源的自动发现。
- 持久化定时任务支持标准五段式 Cron、固定间隔和指定时间单次执行；每次执行创建独立 session，并可指定工作空间、模型、思考强度、任务专属 Skills（含 `--no-skills`）和初始化 Prompt。每个任务可分页查看全部关联 session；普通会话列表可按来源隐藏任务 session，服务端默认在闲置 7 天后自动清理。
- 同一 session 支持多个 WebSocket 客户端共同输入并接收实时输出。
- 严格保持 WebSocket 文本帧与 pi RPC JSONL 记录之间的一对一映射。
- 稳定历史与活动 turn WAL 持久化，重连时可无缝衔接 replay 和实时事件。
- 支持工作区能力探测、目录浏览与创建，以及远程文件读取与下载。
- App 可在独立设置页登录、重新登录和登出 pi 内置 Provider，并查看各 Provider 的模型。
- App 可仅凭普通用户 SSH 凭据自动引导 Node.js/Pi、安装并托管网关：macOS 使用 LaunchAgent、Linux 使用 systemd user、Windows 使用当前用户计划任务。
- 用户提交首条请求后立即并行调用可配置的轻量模型生成会话标题，并同步到所有客户端。
- 提供 token 鉴权、Origin 校验、消息大小限制、慢客户端隔离和优雅退出。

## 快速开始

要求：

- Go 1.26.2 或兼容版本
- `pi` 0.83.0 或更高版本在 `PATH` 中（标题链路使用 `before_agent_start` 与 `session_info_changed`）
- pi 可由 App 配置内置 Provider 认证；`models.json` 与扩展 Provider 仍按 pi 原有方式配置

构建并启动：

```bash
mkdir -p bin
go build -o bin/ohpi-gateway ./cmd/ohpi-gateway

export OHPI_TOKEN="$(openssl rand -hex 32)"
./bin/ohpi-gateway \
  --listen 127.0.0.1:18080 \
  --work-dir /path/to/project
```

默认监听 `127.0.0.1:18080`。确认服务正常：

```bash
curl http://127.0.0.1:18080/healthz
```

生产环境应在 TLS 反向代理后提供 `wss://`。完整部署方式和配置项见[部署与配置](docs/deployment.md)。

## 文档

- [API 与 WebSocket 协议](docs/api.md)：HTTP 会话管理、文件浏览、WebSocket create/attach、历史同步和 pi RPC。
- [架构与持久化](docs/architecture.md)：进程模型、session 生命周期、稳定历史、replay WAL 和关闭语义。
- [部署与配置](docs/deployment.md)：macOS LaunchAgent、命令行参数、环境变量和安全建议。
- [SSH 自动安装模式](docs/managed-install.md)：三平台用户级托管、自动拉起/停止、Windows 与 macOS 签名边界。
- [发布与签名](docs/releasing.md)：Release 产物、Developer ID、Apple notarization 与 CI Secret 轮换。
- [Kotlin Multiplatform 客户端](app/README.md)：Android、桌面和 iOS 客户端的构建说明。

## 开发与测试

```bash
go test ./...
go test -race ./...
go vet ./...
```

测试使用受控的假 pi 子进程，不会调用模型或消耗 API。涉及 WebSocket、队列、进程或关闭流程的变更应运行 race detector。

## License

Oh Pi App 自有代码以 [Apache License 2.0](LICENSE) 授权，见 [NOTICE](NOTICE)。

客户端原生 SSH 栈还包含第三方组件（尤其是 LGPL-2.1+ 的 libssh），完整说明见 [`native/pi_ssh/licenses/`](native/pi_ssh/licenses/)。
