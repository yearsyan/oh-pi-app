# 部署与配置

本文介绍 Oh Pi App 网关（`ohpi-gateway`）的 macOS LaunchAgent 部署、全部运行参数和生产安全建议。首次运行可先参考根目录的[快速开始](../README.md#快速开始)。

## macOS LaunchAgent 部署

仓库提供可重复执行的生产部署脚本。首次执行时传入 token；脚本会构建去除本地路径和调试符号的二进制、安装到 `~/.local/bin/ohpi-gateway`，然后创建并启动当前用户的 LaunchAgent：

```bash
OHPI_TOKEN="$(openssl rand -hex 32)" ./scripts/deploy-launchd.sh
```

之后更新代码时直接重复执行即可；现有 token 和部署配置会被保留：

```bash
./scripts/deploy-launchd.sh
```

脚本默认用当前 Git 描述（例如 `1.10.8` 或 `1.10.8-dirty`）写入网关版本；构建候选版本时可通过 `OHPI_VERSION` 显式指定：

```bash
OHPI_VERSION="1.10.9" ./scripts/deploy-launchd.sh
```

首次部署或需要修改配置时，可通过环境变量覆盖默认值：

```bash
OHPI_TOKEN="<TOKEN>" \
OHPI_LISTEN="0.0.0.0:18080" \
OHPI_DATA_DIR="$HOME/.local/state/oh-pi-app" \
OHPI_WORK_DIR="/path/to/project" \
OHPI_TITLE_MODEL="openai/gpt-5-nano" \
OHPI_PI_COMMAND="/absolute/path/to/pi" \
./scripts/deploy-launchd.sh
```

部署文件及运行状态：

- 二进制：`~/.local/bin/ohpi-gateway`
- 启动包装器：`~/.local/libexec/oh-pi-app/ohpi-gateway-launch`
- LaunchAgent：`~/Library/LaunchAgents/io.github.yearsyan.ohpi.gateway.plist`
- 配置文件：`~/.config/oh-pi-app/config.json`
- token：`~/.config/oh-pi-app/token`（权限 `0600`，不会写入 plist 或命令行）
- session 与日志：`~/.local/state/oh-pi-app/`

```bash
launchctl print "gui/$(id -u)/io.github.yearsyan.ohpi.gateway"
curl http://127.0.0.1:18080/healthz
```

## 配置

部署脚本会创建并维护 `~/.config/oh-pi-app/config.json`。ohpi-gateway 默认读取 `$XDG_CONFIG_HOME/oh-pi-app/config.json`，未设置 `XDG_CONFIG_HOME` 时使用 `~/.config/oh-pi-app/config.json`；文件不存在时继续使用环境变量和内置默认值。也可以通过 `--config` 或 `OHPI_CONFIG_FILE` 指定其他文件；显式指定的文件不存在或内容无效时，进程会拒绝启动。文件使用 JSON 对象格式：

```json
{
  "OHPI_LISTEN": "127.0.0.1:18080",
  "OHPI_DATA_DIR": "/path/to/state",
  "OHPI_WORK_DIR": "/path/to/project",
  "OHPI_TITLE_MODEL": "auto",
  "OHPI_PI_COMMAND": "/absolute/path/to/pi",
  "OHPI_PI_ENV_PATH": "/absolute/path/to/node/bin:/usr/local/bin:/usr/bin:/bin"
}
```

配置文件只接受上面六个字符串字段，未知字段、重复字段、空值或错误类型都会导致启动失败；它不能配置 token。配置优先级为：命令行参数 > 环境变量 > 配置文件 > 内置默认值。传入 `--config=` 可以禁用配置文件读取。

| 参数 | 环境变量 | 默认值 | 说明 |
|---|---|---:|---|
| `--config` | `OHPI_CONFIG_FILE` | `$XDG_CONFIG_HOME/oh-pi-app/config.json` 或 `~/.config/oh-pi-app/config.json` | JSON 配置文件；默认文件不存在时忽略 |
| `--listen` | `OHPI_LISTEN` | `127.0.0.1:18080` | HTTP 监听地址 |
| `--token` | `OHPI_TOKEN` | 无 | 必填鉴权 token |
| `--token-file` | `OHPI_TOKEN_FILE` | 无 | 从仅含一行内容的文件读取 token；显式 token 非空时优先 |
| `--version` | 无 | `false` | 输出构建版本并退出 |
| `--data-dir` | `OHPI_DATA_DIR` | `$XDG_STATE_HOME/oh-pi-app` 或 `~/.local/state/oh-pi-app` | session 持久化目录 |
| `--work-dir` | `OHPI_WORK_DIR` | 当前目录 | 旧会话 attach 的回退目录、`/fs/list` 的浏览起点 |
| `--title-model` | `OHPI_TITLE_MODEL` | `auto` | 首条请求提交后并行生成标题；见下文模型选择 |
| `--pi` | `OHPI_PI_COMMAND` | `pi` | pi 可执行文件 |
| `--pi-env-path` | `OHPI_PI_ENV_PATH` | 继承网关 `PATH` | 只提供给 pi 子进程的 `PATH`；适用于 fnm、nvm 等 Node 安装 |
| `--pi-arg` | 无 | 无 | 额外 pi 参数，可重复 |
| `--allow-origin` | 无 | 同源 | 允许的浏览器 Origin，可重复；`*` 表示全部 |
| `--max-message-bytes` | 无 | `134217728` | 单条 WS 命令和 pi 事件上限 |
| `--session-idle-timeout` | 无 | `5m` | `agent_settled` 后无新 RPC 输入的 pi 进程回收时间 |
| `--shutdown-timeout` | 无 | `10s` | 优雅退出等待时间 |

只需普通用户 SSH 凭据的三平台自动部署方式见 [SSH 自动安装模式](managed-install.md)。

额外 pi 参数示例：

```bash
./bin/ohpi-gateway \
  --pi-arg=--provider \
  --pi-arg=openai \
  --pi-arg=--model \
  --pi-arg=gpt-5
```

`--mode`、`--session*`、`--continue`、`--resume`、`--fork` 和 `--no-session` 由网关管理，不能通过 `--pi-arg` 覆盖。

### 会话标题模型

ohpi 会把内嵌的 pi extension 安装到 `<data-dir>/runtime/ohpi-session-title.ts`，并为每个 pi 子进程显式加载它。extension 在首个 `before_agent_start` 收到已展开的用户请求后立即异步生成标题，不等待主 agent 的回复或工具执行，也不会阻塞主对话、写入对话上下文。生成结果经 pi 的 `session_info_changed` 事件回到网关，由网关持久化并广播。App 在首条用户消息出现时会先显示一个不写回服务端的临时标题，正式模型标题到达后自动替换。手工名称始终优先，生成失败则退化为第一条用户消息。

`OHPI_TITLE_MODEL` 支持：

- `auto`（默认）：从当前会话 provider 已认证、可用的文本模型中选择稳定且较新的 `nano`、`mini`、`flash-lite`、`haiku` 等轻量模型；没有候选时回退当前会话模型，不会把标题内容自动切到另一家 provider。
- `active`：始终使用当前会话模型。最省配置，但主模型较贵时标题也会调用该模型。
- `provider/model-id`：固定模型，适合生产环境。provider 与认证完全复用 pi 的 model registry，网关不读取 API Key。
- `off`：不加载标题 extension，也不自动生成标题。

如果 pi 使用 OpenAI API Key，推荐固定为 `openai/gpt-5-nano`；使用 Google API Key 可固定为 `google/gemini-2.5-flash-lite`。如果使用 ChatGPT/Codex OAuth 或不确定当前认证支持哪些模型，保留 `auto`。OpenRouter 的模型 ID 本身可以含 `/`，例如 `openrouter/openai/gpt-5-nano`。

先在终端启动一次 `pi` 并通过 `/login` 配好对应 provider；extension 读取的仍是 pi 自己的认证存储。不要把 OpenAI、Google 等 provider Key 写入 ohpi 的 JSON 配置，LaunchAgent 也不需要新增一份 Key。

标题请求限制为 128 个输出 token、10 秒超时且最多重试一次。模型支持 `minimal` reasoning 时使用该档位；不支持但允许关闭思考时关闭，只有无法关闭时才选择最低可用档位。实际选中的模型会记录为 pi stderr 日志中的 `[ohpi-title] using provider/model-id`。

## 安全建议

- 生产环境应在 TLS 反向代理后提供 `wss://`，网关本身只监听回环或私网地址，由反向代理负责 TLS、限流和外部访问控制。
- WebSocket token 会出现在请求 URL 中。关闭或脱敏反向代理的 `/ws` query 日志并定期轮换 token；普通 HTTP API 使用 Bearer 请求头。
- 默认只允许无 `Origin` 的非浏览器客户端和同源浏览器连接。跨域前端需要显式配置 `--allow-origin=https://app.example.com`。
- 共享同一个 session 的客户端拥有同等控制权，也会看到彼此的输入、模型响应和工具输出。只把同一个 session ID 发给互相信任的客户端。
- token 文件权限应限制为 `0600`。`OHPI_TOKEN` 不会传入 pi 子进程环境。
