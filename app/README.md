# Oh Pi App — Compose Multiplatform 客户端

基于 ohpi-gateway 的聊天客户端，面向 Android / Desktop (JVM) / iOS 18.5+。
UI 风格参考 DeepSeek / ChatGPT / Codex 等 AI 聊天应用。

## 功能

- **工作空间 / 会话详情**：从网关 HTTP API 读取服务端权威工作空间及分页会话，支持新建、恢复
  （稳定 entry 以 JSONL 持久化到 App 私有目录；attach 只拉本地游标后的分块增量，再从
  磁盘 WAL 追平活动 turn）、重命名和永久删除。活动工作空间和会话由服务端排在最前，
  列表按技术栈显示图标并支持继续加载；长按工作空间可编辑显示名和追加系统提示词。
- **会话内对话**：流式渲染助手消息（Markdown：标题/列表/引用/代码块/行内样式），
  运行中可随时中止（abort）或引导（steer）。
- **思考过程**：可折叠的思考卡片，流式更新。
- **工具调用**：工具卡片展示名称、参数与输出，运行/完成/错误状态实时更新。
- **模型与思考强度切换**：输入框下方的下拉选择；新会话先通过工作空间 `capabilities` 接口
  无 session 预取工作区选项，首条消息创建 session 时应用所选配置，已有会话继续使用
  `get_available_models` / `set_model` 和 `get_available_thinking_levels` / `set_thinking_level`。
- **内置 Provider 配置**：设置中的独立页面列出 pi 内置 Provider 及其模型，支持原生
  API key、多字段凭据、OAuth 浏览器授权和设备码登录，也可移除 pi 保存的凭据。
  `models.json` 与扩展 Provider 不在此页面管理，但它们的模型仍会出现在会话模型选择器中。
- **Slash 指令**：输入 `/` 即展示并筛选当前工作区的 extension、prompt template 和
  `skill:*` 指令；`/compact` 支持可选压缩要求，并映射为原生 compact RPC。
- **自适应布局**：宽度 ≥ 840dp（平板/桌面）为「列表 + 详情」双栏；手机为单栏导航。
- **多语言**：中文 / English / 跟随系统。
- **深色 / 浅色**：跟随系统或手动指定。
- **多服务器**：设置页维护多个 ohpi-gateway（名称 / 地址 / token），随时切换。
- **首启引导**：未配置任何机器时一直显示引导页：先选择「在新机器上安装」或
  「连接已安装的网关」。新机器只需填写 SSH 登录信息即可开始自动安装，并以
  步骤列表展示连接、运行时准备、网关下载、服务安装与启动校验的进度。
- **内置 SSH 隧道**：可通过 SSH 密码或集中管理的私钥连接远端服务器，再让
  WebSocket 与 `/fs/list` 共同复用一条本机回环隧道。Android、Desktop 与 iOS 都
  调用相同的 `libssh` C 核心；首次连接以及主机密钥变化时必须人工确认 SHA-256 指纹。
- **SSH 私钥管理**：可导入已有私钥，或由内置 libssh 在本机生成 Ed25519 密钥对；
  公钥可从密钥的长按菜单查看与复制。一份私钥可被多台机器引用；服务器配置
  只保存密钥 id，不保存私钥内容。iOS 上私钥列表整体存入系统 Keychain（不随
  iCloud 同步、不迁移到新设备），其他平台保存在应用私有设置中。
- **SSH 自动安装**：只填写普通用户 SSH 凭据，App 即可识别 macOS / Linux / Windows，
  在缺少 Pi 时无 sudo 引导用户级 Node.js/Pi，校验并上传 Release 网关二进制，使用
  launchd / systemd user / 当前用户计划任务托管，在连接前自动拉起，并可从设置页停止。完整平台限制见
  [`../docs/managed-install.md`](../docs/managed-install.md)。
- **扩展 UI 对话框**：支持 pi 扩展的 select / confirm / input / editor 请求。

## 架构（shared 模块）

```
data/        ServerProfile、SavedSession、SettingsStore（设备设置与旧列表迁移）
net/         PiClient（Ktor WebSocket；iOS Darwin、其他平台 CIO）、会话/目录 HTTP API、协议 JSON 工具
ssh/         pi_ssh 的 JNI / Kotlin-Native cinterop 绑定
chat/        ChatController（连接 + pi RPC 状态机 + 时间线归约）、Timeline 模型
markdown/    轻量 Markdown 渲染器
i18n/        中英文字符串表
theme/       Material3 深浅色主题 + 语义扩展色
ui/          AppViewModel（导航/服务器/会话/主题）、screens、components
```

协议细节见仓库根目录 `README.md` 的会话管理 HTTP API 与 WebSocket API 两节。

原生实现位于 `../native/pi_ssh/`：稳定 C ABI 封装固定版本的 libssh 0.12.1 与
Mbed TLS 3.6.6，一个 worker 线程复用多个 `direct-tcpip` channel。依赖的完整
许可证、校验和与重链接说明见
[`../native/pi_ssh/licenses/THIRD_PARTY_NOTICES.md`](../native/pi_ssh/licenses/THIRD_PARTY_NOTICES.md)。

## SSH 连接与自动安装

在服务器编辑页选择「SSH 隧道」，然后填写：

- iOS：后端地址固定为 SSH 服务器上的 `127.0.0.1`（不可编辑，TLS 选项隐藏），只需填写后端端口（默认 `18080`）；
- Android / Desktop：网关地址为 SSH 服务器自身看到的 ohpi 地址，通常 `http://127.0.0.1:18080`；
- SSH 主机、端口、用户名；
- SSH 密码，或从集中管理的密钥库选择 / 导入一份 OpenSSH / PEM 私钥（含可选口令）。

新建服务器配置的默认端口全平台为 `18080`。

SSH 模式下网关地址必须使用 `http://` 或 `ws://`。SSH 已加密整条链路；若把
`https://` / `wss://` 改写到随机回环端口，TLS 主机名校验会失效，因此 App 会拒绝
这种配置。首次连接只读取服务器公钥并显示 `SHA256:...` 指纹，确认后才发送凭据；
以后指纹不一致会显示高风险变更提示。

选择「自动安装」时无需填写网关地址和 token；App 固定使用远端回环地址，自动生成
token，并在每次建立连接前确认用户级服务正在运行。此模式不要求管理员密码。Windows
使用密码 SSH 登录时可在无桌面会话下运行；私钥登录受 Task Scheduler 限制，需要当前
用户已有桌面会话。Linux 是否在最后一个登录会话退出后继续运行取决于 linger 设置。

## 运行

- Android：`./gradlew :androidApp:assembleDebug`，安装 `androidApp/build/outputs/apk/debug/`。
  真机连接本机网关时，网关需监听 `0.0.0.0` 并在 App 内填写局域网地址，例如
  `OHPI_TOKEN=xxx ./bin/ohpi-gateway --listen 0.0.0.0:18080 --allow-origin='*'`。
- Desktop：`./gradlew :desktopApp:run`。
- iOS 18.5+：使用 `/iosApp` Xcode 工程入口。

Desktop 原生包已配置 macOS arm64 / x86_64、Linux x86_64 与 Windows x86_64。
Windows 包会编译 WinSock worker 与 JNI DLL，并把 DLL、第三方许可证和裁剪后的 JRE
一并装入 MSI。构建机需要 Java 21、Visual Studio 2022 C++ 工具链、CMake，以及
WiX Toolset 3；PowerShell 构建命令如下：

```powershell
$env:WIX_PATH = "C:\path\to\wix314"
.\gradlew.bat :desktopApp:packageMsi --configure-on-demand
```

输出位于
`desktopApp/build/compose/binaries/main/msi/io.github.yearsyan.ohpi-<version>.msi`。
`WIX_PATH` 必须指向包含 `candle.exe` 与 `light.exe` 的 WiX 目录。

## 备注

- SSH 私钥不会写入远端，SSH 凭据也不会写入日志；Windows 密码安装模式会把同一用户
  密码交给 Task Scheduler 的系统凭据存储。自动安装模式生成的 token 同时以受限权限
  文件保存在远端。设备设置存储本身应由系统账户和磁盘加密保护。
- 会话列表、名称和删除操作都由网关管理；本地仅保留服务器配置、集中管理的 SSH
  私钥（iOS 存于系统 Keychain）、偏好设置，以及升级时使用的一次性旧会话名称迁移数据。
- 删除会话会停止对应 pi 进程并永久删除服务端会话目录，无法从 App 内恢复。
