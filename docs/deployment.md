# 部署与配置

本文介绍 pi2ws 的 macOS LaunchAgent 部署、全部运行参数和生产安全建议。首次运行可先参考根目录的[快速开始](../README.md#快速开始)。

## macOS LaunchAgent 部署

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

## 安全建议

- 生产环境应在 TLS 反向代理后提供 `wss://`，网关本身只监听回环或私网地址，由反向代理负责 TLS、限流和外部访问控制。
- WebSocket token 会出现在请求 URL 中。关闭或脱敏反向代理的 `/ws` query 日志并定期轮换 token；普通 HTTP API 使用 Bearer 请求头。
- 默认只允许无 `Origin` 的非浏览器客户端和同源浏览器连接。跨域前端需要显式配置 `--allow-origin=https://app.example.com`。
- 共享同一个 session 的客户端拥有同等控制权，也会看到彼此的输入、模型响应和工具输出。只把同一个 session ID 发给互相信任的客户端。
- token 文件权限应限制为 `0600`。`PI2WS_TOKEN` 不会传入 pi 子进程环境。
