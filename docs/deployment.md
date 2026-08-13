# 部署与配置

本文介绍 Oh Pi App 网关（`ohpi-gateway`）的快速安装脚本、macOS LaunchAgent 部署、全部运行参数和生产安全建议。首次运行可先参考根目录的[快速开始](../README.md#快速开始)。

## 快速安装脚本（macOS / Linux）

不想自己构建、也不需要 TLS 时，一行命令即可安装并启动最新 Release：

```bash
curl -fsSL https://raw.githubusercontent.com/yearsyan/oh-pi-app/main/scripts/install.sh | sh
```

脚本（`scripts/install.sh`）自包含，不依赖仓库其他文件，执行流程：

1. 探测系统与 CPU 架构（macOS/Linux × amd64/arm64），从 GitHub Release 下载对应二进制并对照 `SHA256SUMS.txt` 校验 SHA-256，再用 `--version` 核对产物版本；
2. 生成 32 字节随机 token（`~/.config/oh-pi-app/token`，权限 `0600`）或保留已有 token；配置文件 `~/.config/oh-pi-app/config.json` 仅在首次写入，之后升级一律保留；
3. 安装二进制到 `~/.local/bin/ohpi-gateway`，写入启动包装器 `~/.local/libexec/oh-pi-app/ohpi-gateway-launch`（从 token 文件读取 token 后 exec 网关，token 不进命令行）；
4. 注册用户级服务并启动：macOS 使用 LaunchAgent（`~/Library/LaunchAgents/io.github.yearsyan.ohpi.gateway.plist`），Linux 优先使用 systemd user unit（`~/.config/systemd/user/ohpi-gateway.service`），systemd user manager 不可用时回退为 `nohup` 后台进程（PID 文件 `~/.local/state/oh-pi-app/gateway.pid`）；
5. 轮询 `http://127.0.0.1:18080/healthz` 确认服务可用后打印 token、监听地址与目录。

前提是 `pi` 已安装且在 `PATH` 中（脚本会解析其绝对路径，fnm 安装会优先稳定路径）。网关启动阶段就需要定位 pi，因此未检测到 `pi` 时脚本会自动安装（与 App SSH 自动安装模式同一套方案）：优先复用系统已兼容的 Node（≥ 22.19）；否则按官方 `SHASUMS256.txt` 校验后下载托管 Node.js 22 到 `~/.local/share/oh-pi-app/node/`，再以 `npm install -g --ignore-scripts --prefix ~/.local` 安装 `pi`（`~/.local/bin/pi`）。全程不调用 sudo，也不修改 shell 启动文件；托管 Node 场景会把 `OHPI_PI_ENV_PATH` 写入配置，保证 pi 子进程能找到 node。设置 `OHPI_NO_PI_INSTALL=1` 可跳过自动安装（缺 pi 时直接报错）。网关只监听回环地址，不涉及 TLS。

重复执行同一命令即升级：下载新版本、替换二进制并重启服务，token、配置与 session 数据全部保留。

### 子命令

`install`（默认）之外还支持 `status` 与 `uninstall`：

```bash
curl -fsSL https://raw.githubusercontent.com/yearsyan/oh-pi-app/main/scripts/install.sh | sh -s status
curl -fsSL https://raw.githubusercontent.com/yearsyan/oh-pi-app/main/scripts/install.sh | sh -s uninstall
```

`status` 打印二进制版本、服务状态与健康检查结果；`uninstall` 停止服务并移除服务定义，但保留二进制、配置、token 与 session 数据（重跑安装会自动复用，与 SSH 自动安装模式的行为一致）。

### 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `OHPI_VERSION` | 最新 Release | 固定安装版本，例如 `2.3.0` |
| `OHPI_REPO` | `yearsyan/oh-pi-app` | 下载来源仓库 |
| `OHPI_LISTEN` | `127.0.0.1:18080` | 网关监听地址（仅首次写入配置） |
| `OHPI_DATA_DIR` | `~/.local/state/oh-pi-app` | session 数据目录（仅首次写入配置） |
| `OHPI_WORK_DIR` | `$HOME` | 默认工作空间目录（仅首次写入配置） |
| `OHPI_TOKEN` | 自动生成 | 显式指定 token（覆盖已有 token 文件）；否则保留已有文件，缺失时自动生成 |
| `OHPI_PI_COMMAND` | 从 PATH 探测 | pi 可执行文件绝对路径 |
| `OHPI_PI_ENV_PATH` | 空；安装 Pi 时生成 | 服务运行 pi 时使用的稳定 `PATH` |
| `OHPI_NO_PI_INSTALL` | 空 | 非空时跳过 pi 自动安装（缺 pi 直接报错） |
| `OHPI_HEALTH_URL` | 由 `OHPI_LISTEN` 推导 | 健康检查地址 |
| `OHPI_NO_SERVICE` | 空 | 非空时只安装二进制与配置，不注册/启动服务 |
| `OHPI_REPLACE_CONFIG` | 空 | 非空时重写已有配置；App 首次托管安装用它固定回环监听和运行路径 |
| `OHPI_REUSE_GATEWAY` | 空 | 非空且已有二进制可用时跳过 Gateway 下载，供 App 离线修复服务定义 |
| `OHPI_TOKEN_STDIN` | 空 | 从标准输入读取且只接受一行 token；成功输出不回显 token，供 App SSH 自动安装调用 |

### 与签名部署脚本的区别

`scripts/deploy-signed-release.sh` 面向 macOS 生产部署：它严格校验 Developer ID、notarization 与固定代码标识，保证 macOS TCC 在升级后仍把网关识别为同一程序，从而保留 Documents、Desktop、Downloads 授权。快速安装脚本不校验签名（Release 二进制本身已由 CI 签名并公证），也不保证 TCC 身份延续；需要该保证时请使用签名部署脚本。

### Linux 注意

- systemd user manager 未运行时（部分 WSL、容器、无桌面会话的机器），脚本回退为 `nohup` 后台进程，不会随登录自动拉起；需要用户退出后仍全天候运行时，由管理员执行 `loginctl enable-linger <username>`。
- launchd/nohup 日志位于 `~/.local/state/oh-pi-app/log/`；systemd 模式使用 `journalctl --user -u ohpi-gateway`。
- 容器内安装建议配合 `OHPI_NO_SERVICE=1` 安装后由容器编排自行管理进程生命周期。

## macOS LaunchAgent 部署

仓库提供严格校验签名身份的本地部署脚本。推荐入口会按当前架构下载 GitHub Release、核对 `SHA256SUMS.txt`，并验证 Developer ID、Apple notarization、固定代码标识 `io.github.yearsyan.ohpi.gateway` 与 Team ID `2XX5KZ6X3G`，然后安装到 `~/.local/bin/ohpi-gateway` 并启动当前用户的 LaunchAgent。首次执行时传入 token：

```bash
OHPI_TOKEN="<TOKEN>" \
  ./scripts/deploy-signed-release.sh deploy
```

之后更新到最新正式 Release 时直接重复执行即可；现有 token、部署配置和 session 数据都会保留：

```bash
./scripts/deploy-signed-release.sh deploy
```

需要固定版本时使用 `--version`：

```bash
./scripts/deploy-signed-release.sh \
  deploy --version 2.3.0
```

`scripts/deploy-launchd.sh` 是底层安装器，本身不负责构建或签名。直接调用时通过 `OHPI_BINARY` 指向已签名并公证的二进制；它既可以来自 Release，也可以是在可信 Mac 上导入 `.p12` 后本机构建、签名并公证的产物。`OHPI_VERSION` 仅用于断言产物版本，不能改写版本或绕过签名：

```bash
OHPI_BINARY="/absolute/path/to/ohpi-gateway-2.3.0-darwin-arm64" \
  ./scripts/deploy-launchd.sh
```

未签名、ad-hoc 签名、版本化/错误 identifier、错误 Team ID、缺少 Hardened Runtime 或可信时间戳、CDHash-only designated requirement、错误架构及未公证产物都会在停止现有服务之前被拒绝。这样 macOS TCC 才能把升级前后的网关视为同一个程序；从旧身份迁移到首个固定身份版本时，用户仍需最后授权一次 Documents、Desktop 或 Downloads。

本机构建与 `.p12` 签名没有被禁止；可复用与 CI 相同的临时 keychain、签名、公证和校验流程，具体命令见[本机构建、签名和公证](releasing.md#本机构建签名和公证)。证书、私钥、密码和公证 API Key 仍不得提交到 Git。

首次部署或需要修改配置时，可通过环境变量覆盖默认值：

```bash
OHPI_TOKEN="<TOKEN>" \
OHPI_LISTEN="0.0.0.0:18080" \
OHPI_DATA_DIR="$HOME/.local/state/oh-pi-app" \
OHPI_WORK_DIR="/path/to/project" \
OHPI_TITLE_MODEL="openai/gpt-5-nano" \
OHPI_PI_COMMAND="/absolute/path/to/pi" \
OHPI_SCHEDULED_SESSION_RETENTION="168h" \
./scripts/deploy-signed-release.sh deploy
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
  "OHPI_PI_ENV_PATH": "/absolute/path/to/node/bin:/usr/local/bin:/usr/bin:/bin",
  "OHPI_PI_ENV_FILE": "~/.zshrc",
  "OHPI_PI_ENV_SHELL": "/bin/zsh",
  "OHPI_SCHEDULED_SESSION_RETENTION": "168h"
}
```

配置文件只接受上面九个字符串字段，未知字段、重复字段、空值或错误类型都会导致启动失败；它不能配置 token。`OHPI_PI_ENV_FILE` 和 `OHPI_PI_ENV_SHELL` 可一起省略，以禁用 shell 环境加载。配置优先级为：命令行参数 > 环境变量 > 配置文件 > 内置默认值。传入 `--config=` 可以禁用配置文件读取。

| 参数 | 环境变量 | 默认值 | 说明 |
|---|---|---:|---|
| `--config` | `OHPI_CONFIG_FILE` | `$XDG_CONFIG_HOME/oh-pi-app/config.json` 或 `~/.config/oh-pi-app/config.json` | JSON 配置文件；默认文件不存在时忽略 |
| `--listen` | `OHPI_LISTEN` | `127.0.0.1:18080` | HTTP 监听地址 |
| `--token` | `OHPI_TOKEN` | 无 | 必填鉴权 token |
| `--token-file` | `OHPI_TOKEN_FILE` | 无 | 从仅含一行内容的文件读取 token；显式 token 非空时优先 |
| `--version` | 无 | `false` | 输出构建版本并退出 |
| `--data-dir` | `OHPI_DATA_DIR` | `$XDG_STATE_HOME/oh-pi-app` 或 `~/.local/state/oh-pi-app` | session 持久化目录 |
| `--work-dir` | `OHPI_WORK_DIR` | 当前目录 | 默认工作空间目录、`/fs/list` 的浏览起点 |
| `--title-model` | `OHPI_TITLE_MODEL` | `auto` | 首条请求提交后并行生成标题；见下文模型选择 |
| `--pi` | `OHPI_PI_COMMAND` | `pi` | pi 可执行文件 |
| `--pi-env-path` | `OHPI_PI_ENV_PATH` | 继承网关 `PATH` | 只提供给 pi 子进程的 `PATH`；适用于 fnm、nvm 等 Node 安装 |
| `--pi-env-file` | `OHPI_PI_ENV_FILE` | 无 | 网关启动时 source 一次，并把其中导出的变量提供给全部 pi 子进程；支持 `~` |
| `--pi-env-shell` | `OHPI_PI_ENV_SHELL` | 按文件名或 `$SHELL` 推断 | source 环境文件所用 shell，例如 `/bin/zsh` 或 `/bin/bash` |
| `--pi-arg` | 无 | 无 | 额外 pi 参数，可重复 |
| `--allow-origin` | 无 | 同源 | 允许的浏览器 Origin，可重复；`*` 表示全部 |
| `--max-message-bytes` | 无 | `134217728` | 单条 WS 命令和 pi 事件上限 |
| `--session-idle-timeout` | 无 | `5m` | `agent_settled` 后无新 RPC 输入的 pi 进程回收时间 |
| `--scheduled-session-retention` | `OHPI_SCHEDULED_SESSION_RETENTION` | `168h`（7 天） | 定时任务会话按最近活跃时间自动清理的保留期，最短 1 小时；修改后重启生效 |
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

### 复用 shell 环境

LaunchAgent、systemd user service 和计划任务不会像交互式终端一样自动读取 `.zshrc` 或 `.bashrc`。配置 `OHPI_PI_ENV_FILE` 后，网关会在初始化时启动一次交互式 shell，显式 source 该文件，捕获导出的环境，然后由会话、能力探测和 Provider 管理进程共同复用。它不会为每个 pi 进程重复执行 rc 文件，也不要求在 plist 或 launcher 外再套一层 shell：

```json
{
  "OHPI_PI_ENV_FILE": "~/.zshrc",
  "OHPI_PI_ENV_SHELL": "/bin/zsh"
}
```

文件名为 `.zshrc`、`.bashrc` 或 `.bash_profile` 时可以省略 shell。`OHPI_PI_ENV_PATH` 如果同时存在，会在 source 完成后覆盖 rc 文件导出的 `PATH`。`OHPI_TOKEN`、`OHPI_TOKEN_FILE` 和环境加载配置本身始终从 pi 子进程环境中移除。

完整的个人 rc 文件可以使用，但其中所有 `export` 的非网关变量都会进入 pi；更严格的生产部署可以改用一个只导出 `PATH`、`JAVA_HOME`、`ANDROID_HOME` 等必要变量的专用文件。rc 文件是在网关用户权限下执行的，因此运行配置 API 只应开放给持有网关 token 的可信客户端。

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
- 运行配置 API 可以指定下次启动时执行的 shell 文件；网关 token 因而应视为远程代码执行级凭据，不能与不可信用户共享。
