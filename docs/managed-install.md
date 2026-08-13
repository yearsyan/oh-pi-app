# SSH 自动安装模式

App 的「自动安装」连接方式只需要远端普通用户的 SSH 登录凭据。首次连接时，App 会识别操作系统与 CPU 架构：macOS/Linux 通过 SSH 在远端拉取并执行仓库的 `scripts/install.sh`，由该脚本下载对应的 `ohpi-gateway` Release 产物并校验 `SHA256SUMS.txt`；Windows 仍由 App 下载、校验后通过 SSH 上传二进制。两条路径都不需要管理员权限。

macOS/Linux 的 Node.js/Pi、Gateway 配置与 launchd/systemd 服务安装均以 `scripts/install.sh` 为唯一实现；Kotlin 只负责探测、快路径、远端脚本启动和最终健康校验。如果 SSH 用户没有可运行的 `pi`，远端脚本会在用户目录安装 Node.js 22 和 `@earendil-works/pi-coding-agent`。Node.js 先以官方 `SHASUMS256.txt` 确定版本与 SHA-256，再对官方源与 npmmirror 做短时测速；Pi/npm 也只在镜像版本与官方一致且至少快 20% 时使用镜像。所有镜像都是候选源，失败会回退，也不会改写 `.npmrc`。Pi 仍以 `npm --ignore-scripts` 方式安装；不会调用 `sudo`，也不会修改 shell 启动文件。Windows 继续使用内置 PowerShell 引导逻辑。

App 生成的 token 作为 SSH 命令的标准输入交给脚本，不放入远端命令行或环境；下载命令也与该标准输入断开。脚本写入权限受限的 token 文件，App 调用模式下不会把 token 回显到标准输出。App 优先从与自身版本一致的 `v*` tag 拉取脚本；尚无 tag 的开发构建可回退到 `main`，但两者都必须匹配 App 内固定的 SHA-256，下载失败时也只允许复用校验一致的缓存 `~/.cache/oh-pi-app/install.sh`。首次安装会重写托管配置以固定远端 `127.0.0.1:18080`；使用同一 token 且 Pi 仍可用时，修复已有服务会保留运行配置并复用已安装的 Gateway 二进制。

App 会在执行脚本时开启专用进度协议。脚本仅在这个模式下输出独立的机器可读事件行，分别表示安装 Pi、下载 Gateway、写入 Gateway 与启动服务。App 对 SSH stdout 做跨分片按行解码，把事件转为进度状态，其他 stdout 及全部 stderr 仍原样实时显示；普通 `curl | sh` 不会输出协议标记。

安装页会在远端命令仍运行时逐行显示脚本 stdout/stderr。日志仅保留最近 300 行，移除 ANSI 控制序列并对当前连接 token 与较长 SSH 密码做防御性脱敏；命令结束后的完整有界输出仍用于错误诊断。

之后每次连接都会检查网关健康状态。服务未运行时，App 会先通过系统的用户级服务管理器拉起它，再建立 SSH 隧道；设置页的电源按钮可以关闭远端网关。关闭只停止服务，不删除二进制、配置、token、会话或服务定义，下一次连接仍会自动拉起。

## 前提条件

- 已有 Pi 时，App 会先检查 SSH 用户的 `PATH`，再检查 fnm、nvm、Volta、pnpm、npm 与 Bun 的常见用户目录，并把稳定化后的绝对路径及 Node 运行 `PATH` 写入网关配置。
- macOS/Linux 需要能通过 HTTPS 访问 `raw.githubusercontent.com` 和 GitHub Release；没有 Pi 时还需访问 `nodejs.org` 和 npm registry，并具备系统自带的 `curl`、`tar` 与 SHA-256 工具。Windows 使用 PowerShell 自带的下载、解压和哈希能力，Gateway 下载发生在 App 侧。
- 远端为 macOS、Linux 或 Windows，CPU 为 `amd64` 或 `arm64`。
- GitHub Release 包含对应的网关二进制和 `SHA256SUMS.txt`。`v*` tag 的 Release workflow 会构建 macOS、Linux、Windows 的 amd64/arm64 二进制以及 Android APK。
- 首次连接时必须人工核对并确认 SSH 主机的 `SHA256:` 指纹。指纹变化会再次要求确认。

App 为每个托管服务器生成 32 字节随机 token。SSH 私钥不会写入远端；Windows 密码模式会把同一用户密码交给 Task Scheduler 的系统凭据存储，其他平台的 SSH 密码只保存在客户端设置中。生成的网关 token 会以仅当前用户可读的文件写入远端。网关只监听远端 `127.0.0.1:18080`，客户端经 SSH 隧道访问，不向公网暴露端口。

## 平台行为

| 平台 | 用户级托管器 | 安装位置 | 无管理员运行条件 |
|---|---|---|---|
| macOS | `launchd` LaunchAgent | `~/.local/bin/ohpi-gateway` | 当前用户的 `gui/$UID` 或 `user/$UID` launchd domain 可用 |
| Linux | 优先 `systemd --user`，否则 nohup | `~/.local/bin/ohpi-gateway` | systemd user manager 不可用时退化为当前用户后台进程 |
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

Release workflow 在 macOS runner 上构建 Darwin 二进制，使用 Team `2XX5KZ6X3G` 的 Developer ID Application、固定代码标识 `io.github.yearsyan.ohpi.gateway`、Hardened Runtime 与可信时间戳签名，并将两个架构一起提交 Apple notarization。固定 designated requirement 让 macOS TCC 在升级后仍把网关识别为同一程序，从而保留用户授予的 Documents、Desktop 与 Downloads 访问。架构独立的已签名裸二进制由远端 `install.sh` 下载，同时 Release 还提供已公证的 Darwin ZIP 供直接分发。完整凭据与轮换要求见[发布与签名](releasing.md)。

`curl` 写入远端临时目录的路径通常不会附带浏览器下载产生的 quarantine 属性，但正式签名仍可满足 Gatekeeper、MDM 和企业安全策略的校验要求。命令行裸二进制不能附加 stapled ticket；Apple 公证服务会在线发布与代码签名对应的 ticket。

## 发布兼容性

macOS/Linux 自动安装依赖与 App 版本相同的 `v*` tag 中 `scripts/install.sh` 的 `OHPI_TOKEN_STDIN` / `OHPI_REUSE_GATEWAY` 接口；App 同时内置该脚本的 SHA-256，因此修改脚本后必须同步更新校验值并发布新 App tag。脚本负责选择最新 Gateway Release。Windows 路径依赖网关的 `--version`、`--token-file`、配置文件中的 `OHPI_PI_COMMAND` / `OHPI_PI_ENV_PATH`。两条路径都要求 `/healthz` 返回 `service` / `version` / `protocol` 字段。
