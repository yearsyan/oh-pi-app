package io.github.yearsyan.ohpi.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yearsyan.ohpi.data.AppLanguage

/** All user-facing strings, one implementation per supported language. */
interface Strings {
    val appName: String
    val appTagline: String

    // onboarding / server setup
    val welcomeTitle: String
    val welcomeBody: String
    val serverSetupTitle: String
    val serverNameLabel: String
    val serverHostLabel: String
    val serverPortLabel: String
    val backendPortLabel: String
    val serverTlsLabel: String
    val serverTokenLabel: String
    val serverNamePlaceholder: String
    val serverHostPlaceholder: String
    val serverTokenPlaceholder: String
    val connectAndSave: String
    val serverHostRequired: String
    val serverPortInvalid: String
    val connectionModeLabel: String
    val directConnection: String
    val sshConnection: String
    val sshGatewayPlaintextHint: String
    val sshGatewayIosFixedHostHint: String
    val sshHostLabel: String
    val sshPortLabel: String
    val sshUsernameLabel: String
    val sshAuthenticationLabel: String
    val sshPasswordAuthentication: String
    val sshPrivateKeyAuthentication: String
    val sshPasswordLabel: String
    val sshPrivateKeyLabel: String
    val sshPrivateKeyPassphraseLabel: String
    val sshTrustedHostKeyLabel: String
    val sshForgetHostKey: String
    val sshTrustOnFirstUseHint: String
    val sshHostRequired: String
    val sshPortInvalid: String
    val sshUsernameRequired: String
    val sshPasswordRequired: String
    val sshPrivateKeyRequired: String
    val sshGatewayTlsInvalid: String

    // SSH host verification
    val sshHostKeyTitle: String
    val sshHostKeyChangedTitle: String
    val sshHostKeyBody: (String) -> String
    val sshHostKeyChangedBody: (String) -> String
    val sshExpectedFingerprint: String
    val sshObservedFingerprint: String
    val sshRejectHostKey: String
    val sshTrustAndConnect: String

    // session list
    val sessionsTitle: String
    val newChat: String
    val noSessions: String
    val noSessionsHint: String
    val rename: String
    val delete: String
    val renameDialogTitle: String
    val sessionNameLabel: String
    val cancel: String
    val confirm: String
    val deleteSessionTitle: String
    val deleteSessionBody: String
    val untitledSession: String
    val sessionRunning: String
    val sessionOutputting: String

    // workspaces
    val workspaceDialogTitle: String
    val workspaceLabel: String
    val workspaceHint: String
    val addWorkspace: String
    val defaultWorkspace: String
    val expandWorkspace: String
    val collapseWorkspace: String
    val showMoreSessions: (Int) -> String
    val showLessSessions: String
    val selectThisDirectory: String
    val upLevel: String
    val noSubdirectories: String
    val createFolder: String
    val folderNameLabel: String

    // file browser
    val browseFiles: String
    val filesEmptyFolder: String
    val retry: String
    val folderLoadFailed: (String) -> String
    val fileOpenFailed: (String) -> String
    val fileTruncatedNotice: String
    val fileViewSource: String
    val fileViewRendered: String
    val apkDownloading: String
    val apkInstallFailed: (String) -> String

    // chat
    val messagePlaceholder: String
    val send: String
    val stop: String
    val scrollToBottom: String
    val addImage: String
    val removeImage: String
    val imageAttachment: (Int) -> String
    val imageTooLarge: String
    val imageReadFailed: String
    val compactCommandDescription: String
    val commandSourceBuiltIn: String
    val commandSourceExtension: String
    val commandSourcePrompt: String
    val commandSourceSkill: String
    val commandSourceCommand: String
    val thinking: String
    val thinkingInProgress: String
    val processThoughtTimes: (Int) -> String
    val processThoughtOnce: String
    val processWroteFiles: (Int) -> String
    val processReadFiles: (Int) -> String
    val processWroteFile: (String) -> String
    val processReadFile: (String) -> String
    val processRanCommands: (Int) -> String
    val processSearchedTimes: (Int) -> String
    val processListedDirectories: (Int) -> String
    val processCalledTools: (Int) -> String
    val processSummarySeparator: String
    val toolRunning: String
    val toolInput: String
    val toolOutput: String
    val toolExecuted: (String) -> String
    val toolRead: (String) -> String
    val toolWrote: (String) -> String
    val toolEdited: (String) -> String
    val toolSearched: (String) -> String
    val toolListed: (String) -> String
    val toolCalled: (String) -> String
    val connected: String
    val connecting: String
    val restoringSession: String
    val syncingLatestActivity: String
    val disconnected: String
    val connectionError: String
    val reconnect: String
    val model: String
    val thinkingLevel: String
    val noModel: String
    val selectModel: String
    val selectThinkingLevel: String
    val loadingModels: String
    val retryModels: String
    val modelOptionsFailed: (String) -> String
    val commandRejected: String
    val notConnected: String
    val abortSent: String
    val agentWorking: String
    val agentWorkingTool: String
    val sessionInfo: String
    val sessionId: String
    val workDir: String
    val resumeCommand: String
    val resumeCommandHint: String
    val sessionUsageTitle: String
    val contextUsage: String
    val contextWindow: String
    val contextUsed: String
    val contextRemaining: String
    val contextUnknown: String
    val cacheUsage: String
    val cacheHitRate: String
    val cacheRead: String
    val cacheWrite: String
    val tokenUsage: String
    val inputTokens: String
    val outputTokens: String
    val totalTokens: String
    val usageUnavailable: String
    val emptyChatTitle: String
    val emptyChatBody: String
    val copied: String
    val copy: String
    val queueSteer: String
    val queueFollowUp: String
    val compacting: String
    val compacted: String
    val retrying: String
    val retryOk: String
    val retryFailed: String
    val agentDone: String
    val stopLength: String
    val stopToolUse: String
    val stopError: String
    val stopAborted: String

    // extension dialog
    val dialogInputHint: String
    val dialogOk: String
    val dialogCancel: String

    // settings
    val settingsTitle: String
    val serversSection: String
    val addServer: String
    val editServer: String
    val deleteServerTitle: String
    val deleteServerBody: String
    val appearanceSection: String
    val themeSystem: String
    val themeLight: String
    val themeDark: String
    val languageSection: String
    val languageSystem: String
    val aboutSection: String
    val activeServerHint: String

    // about / open-source licenses
    val openSourceLicenses: String
    val licensesIntro: String
    val licenseApache20: String
    val licenseMit: String
    val licenseLgpl21: String
    val licensesFrameworks: String
    val licensesNative: String
    val licensesThisApp: String
    val licenseFullText: String
    val licensesRelinkingTitle: String
    val licensesRelinkingBody: String
    val licensesVersion: String
    val licenseCopyright: String

    // misc
    val back: String
    val justNow: String
    val minutesAgo: (Int) -> String
    val hoursAgo: (Int) -> String
    val daysAgo: (Int) -> String
}

object EnStrings : Strings {
    override val appName = "Oh Pi App"
    override val appTagline = "Your coding agent, anywhere"
    override val welcomeTitle = "Welcome to Pi"
    override val welcomeBody = "Connect to a ohpi gateway to chat with your pi coding agent — watch it think, call tools and write code in real time."
    override val serverSetupTitle = "Set up your first server"
    override val serverNameLabel = "Name"
    override val serverHostLabel = "Gateway host / IP"
    override val serverPortLabel = "Port"
    override val backendPortLabel = "Backend port"
    override val serverTlsLabel = "Use TLS"
    override val serverTokenLabel = "Token"
    override val serverNamePlaceholder = "My workstation"
    override val serverHostPlaceholder = "192.168.1.10"
    override val serverTokenPlaceholder = "OHPI_TOKEN"
    override val connectAndSave = "Save & Connect"
    override val serverHostRequired = "Gateway host is required"
    override val serverPortInvalid = "Gateway port must be between 1 and 65535"
    override val connectionModeLabel = "Connection"
    override val directConnection = "Direct"
    override val sshConnection = "SSH tunnel"
    override val sshGatewayPlaintextHint = "Use the gateway host and port as seen by the SSH server. TLS is disabled because SSH encrypts the connection."
    override val sshGatewayIosFixedHostHint = "The backend address is fixed to 127.0.0.1 on the SSH server; only the backend port is needed."
    override val sshHostLabel = "SSH host"
    override val sshPortLabel = "Port"
    override val sshUsernameLabel = "SSH username"
    override val sshAuthenticationLabel = "Authentication"
    override val sshPasswordAuthentication = "Password"
    override val sshPrivateKeyAuthentication = "Private key"
    override val sshPasswordLabel = "SSH password"
    override val sshPrivateKeyLabel = "Private key contents"
    override val sshPrivateKeyPassphraseLabel = "Key passphrase (optional)"
    override val sshTrustedHostKeyLabel = "Trusted host key"
    override val sshForgetHostKey = "Forget key"
    override val sshTrustOnFirstUseHint = "The server fingerprint will be shown for confirmation before credentials are sent."
    override val sshHostRequired = "SSH host is required"
    override val sshPortInvalid = "SSH port must be between 1 and 65535"
    override val sshUsernameRequired = "SSH username is required"
    override val sshPasswordRequired = "SSH password is required"
    override val sshPrivateKeyRequired = "Private key contents are required"
    override val sshGatewayTlsInvalid = "SSH mode requires ws:// or http://; the SSH tunnel already provides encryption"

    override val sshHostKeyTitle = "Trust this SSH server?"
    override val sshHostKeyChangedTitle = "SSH host key changed"
    override val sshHostKeyBody = { host: String -> "Verify this fingerprint for $host before connecting. Credentials have not been sent yet." }
    override val sshHostKeyChangedBody = { host: String -> "The key presented by $host differs from the trusted key. This can indicate a server reinstall or an attack. Only continue after verifying it." }
    override val sshExpectedFingerprint = "Previously trusted"
    override val sshObservedFingerprint = "Presented now"
    override val sshRejectHostKey = "Cancel"
    override val sshTrustAndConnect = "Trust & connect"

    override val sessionsTitle = "Chats"
    override val newChat = "New chat"
    override val noSessions = "No chats yet"
    override val noSessionsHint = "Start a new chat to talk with your agent."
    override val rename = "Rename"
    override val delete = "Delete"
    override val renameDialogTitle = "Rename chat"
    override val sessionNameLabel = "Chat name"
    override val cancel = "Cancel"
    override val confirm = "Save"
    override val deleteSessionTitle = "Delete chat?"
    override val deleteSessionBody = "This permanently deletes the chat and its history from the server. This cannot be undone."
    override val untitledSession = "Untitled chat"
    override val sessionRunning = "Running"
    override val sessionOutputting = "Outputting"

    override val workspaceDialogTitle = "Choose a workspace"
    override val workspaceLabel = "Workspace"
    override val workspaceHint = "Absolute path"
    override val addWorkspace = "Add workspace"
    override val defaultWorkspace = "Default workspace"
    override val expandWorkspace = "Expand workspace"
    override val collapseWorkspace = "Collapse workspace"
    override val showMoreSessions = { count: Int -> "Show $count more" }
    override val showLessSessions = "Show less"
    override val selectThisDirectory = "Select this directory"
    override val upLevel = "Up"
    override val noSubdirectories = "No subdirectories"
    override val createFolder = "New folder"
    override val folderNameLabel = "Folder name"

    override val browseFiles = "Browse files"
    override val filesEmptyFolder = "This folder is empty"
    override val retry = "Retry"
    override val folderLoadFailed = { error: String -> "Could not open folder: $error" }
    override val fileOpenFailed = { error: String -> "Could not open file: $error" }
    override val fileTruncatedNotice = "Large file — showing the beginning only"
    override val fileViewSource = "Source"
    override val fileViewRendered = "Preview"
    override val apkDownloading = "Downloading APK…"
    override val apkInstallFailed = { error: String -> "Could not install APK: $error" }

    override val messagePlaceholder = "Message Pi…"
    override val send = "Send"
    override val stop = "Stop"
    override val scrollToBottom = "Scroll to bottom"
    override val addImage = "Add image"
    override val removeImage = "Remove image"
    override val imageAttachment = { count: Int -> if (count == 1) "Image" else "$count images" }
    override val imageTooLarge = "The image must be smaller than 8 MB"
    override val imageReadFailed = "Could not read this image"
    override val compactCommandDescription = "Compact conversation context; optional instructions may follow"
    override val commandSourceBuiltIn = "Built-in"
    override val commandSourceExtension = "Extension"
    override val commandSourcePrompt = "Prompt"
    override val commandSourceSkill = "Skill"
    override val commandSourceCommand = "Command"
    override val thinking = "Thinking"
    override val thinkingInProgress = "Thinking"
    override val processThoughtTimes = { n: Int -> "Thought $n ${if (n == 1) "time" else "times"}" }
    override val processThoughtOnce = "thought"
    override val processWroteFiles = { n: Int -> "wrote $n ${if (n == 1) "file" else "files"}" }
    override val processReadFiles = { n: Int -> "read $n ${if (n == 1) "file" else "files"}" }
    override val processWroteFile = { name: String -> "wrote $name" }
    override val processReadFile = { name: String -> "read $name" }
    override val processRanCommands = { n: Int -> "ran $n ${if (n == 1) "command" else "commands"}" }
    override val processSearchedTimes = { n: Int -> "searched $n ${if (n == 1) "time" else "times"}" }
    override val processListedDirectories = { n: Int ->
        "listed $n ${if (n == 1) "directory" else "directories"}"
    }
    override val processCalledTools = { n: Int -> "used $n ${if (n == 1) "tool" else "tools"}" }
    override val processSummarySeparator = ", "
    override val toolRunning = "Running"
    override val toolInput = "Input"
    override val toolOutput = "Output"
    override val toolExecuted = { target: String -> "Ran $target" }
    override val toolRead = { target: String -> "Read $target" }
    override val toolWrote = { target: String -> "Wrote $target" }
    override val toolEdited = { target: String -> "Edited $target" }
    override val toolSearched = { target: String -> "Searched for $target" }
    override val toolListed = { target: String -> "Listed $target" }
    override val toolCalled = { target: String -> "Used $target" }
    override val connected = "Online"
    override val connecting = "Connecting"
    override val restoringSession = "Restoring session"
    override val syncingLatestActivity = "Syncing latest activity"
    override val disconnected = "Offline"
    override val connectionError = "Connection failed"
    override val reconnect = "Reconnect"
    override val model = "Model"
    override val thinkingLevel = "Thinking"
    override val noModel = "Default"
    override val selectModel = "Select model"
    override val selectThinkingLevel = "Thinking effort"
    override val loadingModels = "Loading models…"
    override val retryModels = "Retry models"
    override val modelOptionsFailed = { reason: String -> "Could not load model options: $reason" }
    override val commandRejected = "Command rejected"
    override val notConnected = "Not connected"
    override val abortSent = "Stop requested"
    override val agentWorking = "Working"
    override val agentWorkingTool = "Running"
    override val sessionInfo = "Session info"
    override val sessionId = "Session ID"
    override val workDir = "Working directory"
    override val resumeCommand = "Resume in terminal"
    override val resumeCommandHint = "Run on the machine running the ohpi gateway"
    override val sessionUsageTitle = "Session usage"
    override val contextUsage = "Context usage"
    override val contextWindow = "Context window"
    override val contextUsed = "Used"
    override val contextRemaining = "Remaining"
    override val contextUnknown = "Available after the next model response"
    override val cacheUsage = "Prompt cache"
    override val cacheHitRate = "Cache hit rate (total)"
    override val cacheRead = "Cache read"
    override val cacheWrite = "Cache write"
    override val tokenUsage = "Token usage"
    override val inputTokens = "Input"
    override val outputTokens = "Output"
    override val totalTokens = "Total"
    override val usageUnavailable = "Usage data is not available yet"
    override val emptyChatTitle = "How can I help?"
    override val emptyChatBody = "Ask anything, or let the agent inspect your project."
    override val copied = "Copied"
    override val copy = "Copy"
    override val queueSteer = "steer queued"
    override val queueFollowUp = "follow-up queued"
    override val compacting = "Compacting context…"
    override val compacted = "Context compacted"
    override val retrying = "Retrying"
    override val retryOk = "Retry succeeded"
    override val retryFailed = "Retry failed"
    override val agentDone = "Done"
    override val stopLength = "Reached length limit"
    override val stopToolUse = "Continue after tool calls"
    override val stopError = "Error"
    override val stopAborted = "Aborted"

    override val dialogInputHint = "Type your answer…"
    override val dialogOk = "OK"
    override val dialogCancel = "Cancel"

    override val settingsTitle = "Settings"
    override val serversSection = "Servers"
    override val addServer = "Add server"
    override val editServer = "Edit server"
    override val deleteServerTitle = "Delete server?"
    override val deleteServerBody = "This only removes the server profile from this device. Sessions on the server are not deleted."
    override val appearanceSection = "Appearance"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val languageSection = "Language"
    override val languageSystem = "System"
    override val aboutSection = "About"
    override val activeServerHint = "Active"

    override val openSourceLicenses = "Open-source licenses"
    override val licensesIntro =
        "This app is built on open-source software. We gratefully acknowledge the following projects and their contributors."
    override val licenseApache20 = "Apache License 2.0"
    override val licenseMit = "MIT License"
    override val licenseLgpl21 = "GNU LGPL v2.1 or later"
    override val licensesFrameworks = "Frameworks & libraries"
    override val licensesNative = "Native libraries"
    override val licensesThisApp = "This app"
    override val licenseFullText = "Full license text"
    override val licensesRelinkingTitle = "Relinkable LGPL components"
    override val licensesRelinkingBody =
        "libssh (LGPL 2.1+) is statically linked into this app. Under the LGPL you may request, for a charge no more than the cost of physically performing this, the object files and build scripts required to relink the app with a modified version of libssh. Contact the developer to receive them."
    override val licensesVersion = "Version"
    override val licenseCopyright = "Copyright"

    override val back = "Back"
    override val justNow = "just now"
    override val minutesAgo = { m: Int -> "${m}m ago" }
    override val hoursAgo = { h: Int -> "${h}h ago" }
    override val daysAgo = { d: Int -> "${d}d ago" }
}

object ZhStrings : Strings {
    override val appName = "Oh Pi App"
    override val appTagline = "随身携带的编码智能体"
    override val welcomeTitle = "欢迎使用 Pi"
    override val welcomeBody = "连接 ohpi 网关，与你的 pi 编码智能体对话——实时查看它的思考、工具调用与代码编写过程。"
    override val serverSetupTitle = "配置第一台服务器"
    override val serverNameLabel = "名称"
    override val serverHostLabel = "网关主机 / IP"
    override val serverPortLabel = "端口"
    override val backendPortLabel = "后端端口"
    override val serverTlsLabel = "启用 TLS"
    override val serverTokenLabel = "令牌"
    override val serverNamePlaceholder = "我的工作站"
    override val serverHostPlaceholder = "192.168.1.10"
    override val serverTokenPlaceholder = "OHPI_TOKEN"
    override val connectAndSave = "保存并连接"
    override val serverHostRequired = "网关主机不能为空"
    override val serverPortInvalid = "网关端口必须在 1 到 65535 之间"
    override val connectionModeLabel = "连接方式"
    override val directConnection = "直接连接"
    override val sshConnection = "SSH 隧道"
    override val sshGatewayPlaintextHint = "填写 SSH 服务器看到的网关主机和端口；连接已由 SSH 加密，因此不启用 TLS。"
    override val sshGatewayIosFixedHostHint = "后端地址固定为 SSH 服务器上的 127.0.0.1，只需填写后端端口。"
    override val sshHostLabel = "SSH 主机"
    override val sshPortLabel = "端口"
    override val sshUsernameLabel = "SSH 用户名"
    override val sshAuthenticationLabel = "认证方式"
    override val sshPasswordAuthentication = "密码"
    override val sshPrivateKeyAuthentication = "私钥"
    override val sshPasswordLabel = "SSH 密码"
    override val sshPrivateKeyLabel = "私钥内容"
    override val sshPrivateKeyPassphraseLabel = "私钥口令（可选）"
    override val sshTrustedHostKeyLabel = "已信任主机密钥"
    override val sshForgetHostKey = "忘记密钥"
    override val sshTrustOnFirstUseHint = "发送凭据前会先显示服务器指纹，由你确认是否信任。"
    override val sshHostRequired = "SSH 主机不能为空"
    override val sshPortInvalid = "SSH 端口必须在 1 到 65535 之间"
    override val sshUsernameRequired = "SSH 用户名不能为空"
    override val sshPasswordRequired = "SSH 密码不能为空"
    override val sshPrivateKeyRequired = "私钥内容不能为空"
    override val sshGatewayTlsInvalid = "SSH 模式需使用 ws:// 或 http://；SSH 隧道本身已提供加密"

    override val sshHostKeyTitle = "信任这台 SSH 服务器？"
    override val sshHostKeyChangedTitle = "SSH 主机密钥已变化"
    override val sshHostKeyBody = { host: String -> "连接 $host 前请核对以下指纹。当前尚未发送认证凭据。" }
    override val sshHostKeyChangedBody = { host: String -> "$host 提供的密钥与已信任密钥不同，可能是服务器重装，也可能存在攻击。请核实后再继续。" }
    override val sshExpectedFingerprint = "原已信任"
    override val sshObservedFingerprint = "本次提供"
    override val sshRejectHostKey = "取消"
    override val sshTrustAndConnect = "信任并连接"

    override val sessionsTitle = "会话"
    override val newChat = "新会话"
    override val noSessions = "暂无会话"
    override val noSessionsHint = "开始一个新会话，与智能体对话吧。"
    override val rename = "重命名"
    override val delete = "删除"
    override val renameDialogTitle = "重命名会话"
    override val sessionNameLabel = "会话名称"
    override val cancel = "取消"
    override val confirm = "保存"
    override val deleteSessionTitle = "删除会话？"
    override val deleteSessionBody = "这会永久删除服务器上的会话及其历史记录，且无法撤销。"
    override val untitledSession = "未命名会话"
    override val sessionRunning = "运行中"
    override val sessionOutputting = "输出中"

    override val workspaceDialogTitle = "选择工作区"
    override val workspaceLabel = "工作区"
    override val workspaceHint = "绝对路径"
    override val addWorkspace = "添加工作区"
    override val defaultWorkspace = "默认工作区"
    override val expandWorkspace = "展开工作区"
    override val collapseWorkspace = "收起工作区"
    override val showMoreSessions = { count: Int -> "展开剩余 $count 条" }
    override val showLessSessions = "收起"
    override val selectThisDirectory = "选择此目录"
    override val upLevel = "上一级"
    override val noSubdirectories = "没有子目录"
    override val createFolder = "新建文件夹"
    override val folderNameLabel = "文件夹名称"

    override val browseFiles = "浏览文件"
    override val filesEmptyFolder = "此文件夹为空"
    override val retry = "重试"
    override val folderLoadFailed = { error: String -> "无法打开文件夹：$error" }
    override val fileOpenFailed = { error: String -> "无法打开文件：$error" }
    override val fileTruncatedNotice = "文件过大，仅显示开头部分"
    override val fileViewSource = "源码"
    override val fileViewRendered = "预览"
    override val apkDownloading = "正在下载 APK…"
    override val apkInstallFailed = { error: String -> "无法安装 APK：$error" }

    override val messagePlaceholder = "给 Pi 发送消息…"
    override val send = "发送"
    override val stop = "停止"
    override val scrollToBottom = "滚动到底部"
    override val addImage = "添加图片"
    override val removeImage = "移除图片"
    override val imageAttachment = { count: Int -> "图片 × $count" }
    override val imageTooLarge = "图片不能超过 8 MB"
    override val imageReadFailed = "无法读取这张图片"
    override val compactCommandDescription = "压缩会话上下文；可在后面附加压缩要求"
    override val commandSourceBuiltIn = "内置"
    override val commandSourceExtension = "扩展"
    override val commandSourcePrompt = "提示词"
    override val commandSourceSkill = "Skill"
    override val commandSourceCommand = "指令"
    override val thinking = "思考"
    override val thinkingInProgress = "正在思考"
    override val processThoughtTimes = { n: Int -> "思考 $n 次" }
    override val processThoughtOnce = "进行了思考"
    override val processWroteFiles = { n: Int -> "写入 $n 个文件" }
    override val processReadFiles = { n: Int -> "读取 $n 个文件" }
    override val processWroteFile = { name: String -> "写入 $name" }
    override val processReadFile = { name: String -> "读取 $name" }
    override val processRanCommands = { n: Int -> "执行 $n 条命令" }
    override val processSearchedTimes = { n: Int -> "搜索 $n 次" }
    override val processListedDirectories = { n: Int -> "查看 $n 个目录" }
    override val processCalledTools = { n: Int -> "调用 $n 次工具" }
    override val processSummarySeparator = "，"
    override val toolRunning = "运行中"
    override val toolInput = "输入"
    override val toolOutput = "输出"
    override val toolExecuted = { target: String -> "执行 $target" }
    override val toolRead = { target: String -> "读取 $target" }
    override val toolWrote = { target: String -> "写入 $target" }
    override val toolEdited = { target: String -> "编辑 $target" }
    override val toolSearched = { target: String -> "搜索 $target" }
    override val toolListed = { target: String -> "查看 $target" }
    override val toolCalled = { target: String -> "调用 $target" }
    override val connected = "在线"
    override val connecting = "连接中"
    override val restoringSession = "正在恢复会话"
    override val syncingLatestActivity = "正在同步最新进度"
    override val disconnected = "离线"
    override val connectionError = "连接失败"
    override val reconnect = "重新连接"
    override val model = "模型"
    override val thinkingLevel = "思考强度"
    override val noModel = "默认"
    override val selectModel = "选择模型"
    override val selectThinkingLevel = "思考强度"
    override val loadingModels = "正在加载模型…"
    override val retryModels = "重试加载模型"
    override val modelOptionsFailed = { reason: String -> "无法加载模型选项：$reason" }
    override val commandRejected = "命令被拒绝"
    override val notConnected = "尚未连接"
    override val abortSent = "已发送中止请求"
    override val agentWorking = "运行中"
    override val agentWorkingTool = "执行中"
    override val sessionInfo = "会话信息"
    override val sessionId = "会话 ID"
    override val workDir = "工作目录"
    override val resumeCommand = "命令行恢复指令"
    override val resumeCommandHint = "在运行 ohpi 网关的机器上执行"
    override val sessionUsageTitle = "会话用量"
    override val contextUsage = "上下文占用"
    override val contextWindow = "上下文窗口"
    override val contextUsed = "已占用"
    override val contextRemaining = "剩余"
    override val contextUnknown = "将在下一次模型响应后更新"
    override val cacheUsage = "提示词缓存"
    override val cacheHitRate = "缓存命中率（累计）"
    override val cacheRead = "缓存读取"
    override val cacheWrite = "缓存写入"
    override val tokenUsage = "Token 用量"
    override val inputTokens = "输入"
    override val outputTokens = "输出"
    override val totalTokens = "总计"
    override val usageUnavailable = "暂时没有可用的用量数据"
    override val emptyChatTitle = "有什么可以帮你？"
    override val emptyChatBody = "随便问点什么，或让智能体检查当前项目。"
    override val copied = "已复制"
    override val copy = "复制"
    override val queueSteer = "条引导排队中"
    override val queueFollowUp = "条跟进排队中"
    override val compacting = "正在压缩上下文…"
    override val compacted = "上下文压缩完成"
    override val retrying = "正在重试"
    override val retryOk = "重试成功"
    override val retryFailed = "重试失败"
    override val agentDone = "本轮完成"
    override val stopLength = "已达长度上限"
    override val stopToolUse = "工具调用后继续"
    override val stopError = "出错"
    override val stopAborted = "已中止"

    override val dialogInputHint = "输入你的回答…"
    override val dialogOk = "确定"
    override val dialogCancel = "取消"

    override val settingsTitle = "设置"
    override val serversSection = "服务器"
    override val addServer = "添加服务器"
    override val editServer = "编辑服务器"
    override val deleteServerTitle = "删除服务器？"
    override val deleteServerBody = "这只会从本设备移除服务器配置，不会删除服务器上的会话。"
    override val appearanceSection = "外观"
    override val themeSystem = "跟随系统"
    override val themeLight = "浅色"
    override val themeDark = "深色"
    override val languageSection = "语言"
    override val languageSystem = "跟随系统"
    override val aboutSection = "关于"
    override val activeServerHint = "当前"

    override val openSourceLicenses = "开源许可证"
    override val licensesIntro =
        "本应用基于开源软件构建。我们在此感谢以下项目及其贡献者。"
    override val licenseApache20 = "Apache 许可证 2.0"
    override val licenseMit = "MIT 许可证"
    override val licenseLgpl21 = "GNU LGPL v2.1 及之后版本"
    override val licensesFrameworks = "框架与库"
    override val licensesNative = "原生库"
    override val licensesThisApp = "本应用"
    override val licenseFullText = "完整许可证文本"
    override val licensesRelinkingTitle = "可重新链接的 LGPL 组件"
    override val licensesRelinkingBody =
        "libssh（LGPL 2.1+）以静态方式链接进本应用。依据 LGPL，你可以索取重新链接本应用所需的目标文件与构建脚本，以便用修改过的 libssh 重新链接，费用不超过物理传输的成本。请联系开发者获取。"
    override val licensesVersion = "版本"
    override val licenseCopyright = "版权"

    override val back = "返回"
    override val justNow = "刚刚"
    override val minutesAgo = { m: Int -> "${m} 分钟前" }
    override val hoursAgo = { h: Int -> "${h} 小时前" }
    override val daysAgo = { d: Int -> "${d} 天前" }
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings }

/** Current language strings, provided by [io.github.yearsyan.ohpi.App]. */
val S: Strings
    @Composable get() = LocalStrings.current

fun stringsFor(language: AppLanguage, systemIsChinese: Boolean): Strings = when (language) {
    AppLanguage.English -> EnStrings
    AppLanguage.Chinese -> ZhStrings
    AppLanguage.System -> if (systemIsChinese) ZhStrings else EnStrings
}
