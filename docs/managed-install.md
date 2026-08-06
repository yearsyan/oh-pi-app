# SSH 自动安装模式

App 的「自动安装」连接方式只需要远端普通用户的 SSH 登录凭据。首次连接时，App 会识别操作系统与 CPU 架构，下载对应的 `ohpi-gateway` Release 产物并校验 `SHA256SUMS.txt`，再通过 SSH 的标准输入直接写入远端；网关安装不依赖远端访问 GitHub，也不需要管理员权限。

如果 SSH 用户还没有可运行的 `pi`，App 会先在用户目录自动安装 Node.js 22 和 `@earendil-works/pi-coding-agent`。Node.js 归档根据官方 `SHASUMS256.txt` 做 SHA-256 校验，Pi 使用官方推荐的 `npm --ignore-scripts` 方式安装；不会调用 `sudo`，也不会修改 shell 启动文件。已有 Pi 时保持原安装不变。

之后每次连接都会检查网关健康状态。服务未运行时，App 会先通过系统的用户级服务管理器拉起它，再建立 SSH 隧道；设置页的电源按钮可以关闭远端网关。关闭只停止服务，不删除二进制、配置、token、会话或服务定义，下一次连接仍会自动拉起。

## 前提条件

- 已有 Pi 时，App 会先检查 SSH 用户的 `PATH`，再检查 fnm、nvm、Volta、pnpm、npm 与 Bun 的常见用户目录，并把稳定化后的绝对路径及 Node 运行 `PATH` 写入网关配置。
- 没有 Pi 时，远端需要能通过 HTTPS 访问 `nodejs.org` 和 npm registry；macOS / Linux 还需要系统自带的 `curl`、`tar` 与 SHA-256 工具。Windows 使用 PowerShell 自带的下载、解压和哈希能力。
- 远端为 macOS、Linux 或 Windows，CPU 为 `amd64` 或 `arm64`。
- GitHub Release 包含对应的网关二进制和 `SHA256SUMS.txt`。`v*` tag 的 Release workflow 会构建 macOS、Linux、Windows 的 amd64/arm64 二进制以及 Android APK。
- 首次连接时必须人工核对并确认 SSH 主机的 `SHA256:` 指纹。指纹变化会再次要求确认。

App 为每个托管服务器生成 32 字节随机 token。SSH 私钥不会写入远端；Windows 密码模式会把同一用户密码交给 Task Scheduler 的系统凭据存储，其他平台的 SSH 密码只保存在客户端设置中。生成的网关 token 会以仅当前用户可读的文件写入远端。网关只监听远端 `127.0.0.1:18080`，客户端经 SSH 隧道访问，不向公网暴露端口。

## 平台行为

| 平台 | 用户级托管器 | 安装位置 | 无管理员运行条件 |
|---|---|---|---|
| macOS | `launchd` LaunchAgent | `~/.local/bin/ohpi-gateway` | 当前用户的 `gui/$UID` 或 `user/$UID` launchd domain 可用 |
| Linux | `systemd --user` | `~/.local/bin/ohpi-gateway` | SSH 登录启动了 systemd user manager |
| Windows | Task Scheduler 当前用户任务 `OhPi Gateway` | `%LOCALAPPDATA%\OhPi\bin\ohpi-gateway.exe` | 见下方 Windows 说明 |

自动安装的 Pi 位于 macOS / Linux 的 `~/.local/bin/pi`，其托管 Node.js 位于 `~/.local/share/oh-pi-app/node/`；Windows 分别位于 `%LOCALAPPDATA%\OhPi\pi` 与 `%LOCALAPPDATA%\OhPi\node`。

配置和运行数据分别位于：

- macOS / Linux：`~/.config/oh-pi-app/` 与 `~/.local/state/oh-pi-app/`；
- Windows：`%LOCALAPPDATA%\OhPi\config` 与 `%LOCALAPPDATA%\OhPi\data`。

### Linux 与 linger

App 不会尝试提权，也不会自行修改系统登录策略。没有启用 linger 时，某些发行版会在该用户最后一个登录会话退出后停止 systemd user manager；App 保持 SSH 隧道期间服务会运行，并会在下一次连接时重新拉起。

如果确实需要用户退出后仍全天候运行，可由服务器管理员按当地策略执行：

```bash
loginctl enable-linger <username>
```

### Windows

Windows 不使用需要管理员权限的系统 Service，而是注册 `RunLevel Limited` 的当前用户计划任务：

- 使用 SSH 密码认证时，同一普通用户密码会经加密 SSH 会话传给 Task Scheduler。计划任务使用 `Password` 登录类型，因此无需桌面会话也能运行，并保留 `pi` 所需的网络访问；不需要管理员密码。
- 使用 SSH 私钥认证时，App 没有 Windows 账户密码可交给 Task Scheduler，因此使用 `Interactive` 登录类型；该用户必须已有桌面登录会话。纯 SSH 的无界面 Windows 主机应改用密码认证，或由管理员预先部署 Windows Service。

这里不使用不保存密码的 S4U 登录类型，因为 Windows 明确限制 S4U 任务访问网络和加密文件；这会破坏 `pi` 对模型 API 和部分用户凭据的访问。

网关也会识别 npm 在 Windows 上生成的 `pi.cmd` / `pi.bat` 入口，通过 UTF-8 PowerShell 桥接启动它，并在强制关闭 session 时回收整个子进程树；不要求 `pi` 额外提供原生 `.exe`。

## macOS 签名

当前路径是通过 SSH 直接写入命令行二进制，不会经过浏览器下载的隔离流程，因此 Developer ID 签名和 notarization 不是此安装模式启动网关的硬性前提。Go 的 Darwin/arm64 链接器也会生成系统加载所需的 ad-hoc 签名。

如果未来把网关作为浏览器下载、`.pkg`、`.dmg` 或图形 `.app` 分发，则应使用 Developer ID 签名并提交 Apple notarization；受 MDM 或企业安全策略约束的机器也可能要求正式签名。Release workflow 目前没有 Apple 签名凭据，因此发布的是未做 Developer ID/notarization 的 CLI 产物。

## 发布兼容性

自动安装依赖新版网关的 `--version`、`--token-file`、配置文件中的 `OHPI_PI_COMMAND` / `OHPI_PI_ENV_PATH`，以及带 `service` / `version` / `protocol` 字段的 `/healthz`。实现这些能力后必须再发布一个新的 `v*` tag；旧 Release 不能用于验证完整安装流程。
