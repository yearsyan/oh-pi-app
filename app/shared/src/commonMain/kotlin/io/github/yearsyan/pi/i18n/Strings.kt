package io.github.yearsyan.pi.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.yearsyan.pi.data.AppLanguage

/** All user-facing strings, one implementation per supported language. */
interface Strings {
    val appName: String
    val appTagline: String

    // onboarding / server setup
    val welcomeTitle: String
    val welcomeBody: String
    val serverSetupTitle: String
    val serverNameLabel: String
    val serverUrlLabel: String
    val serverTokenLabel: String
    val serverNamePlaceholder: String
    val serverUrlPlaceholder: String
    val serverTokenPlaceholder: String
    val connectAndSave: String
    val serverUrlInvalid: String
    val serverRequired: String

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

    // workspaces
    val workspaceDialogTitle: String
    val workspaceLabel: String
    val workspaceHint: String
    val defaultWorkspace: String
    val selectThisDirectory: String
    val upLevel: String
    val noSubdirectories: String

    // chat
    val messagePlaceholder: String
    val messagePlaceholderStreaming: String
    val send: String
    val stop: String
    val thinking: String
    val thinkingInProgress: String
    val toolRunning: String
    val toolInput: String
    val toolOutput: String
    val connected: String
    val connecting: String
    val disconnected: String
    val connectionError: String
    val reconnect: String
    val model: String
    val thinkingLevel: String
    val noModel: String
    val selectModel: String
    val selectThinkingLevel: String
    val newSessionCreated: String
    val sessionAttached: String
    val piCrashed: String
    val connectionClosed: String
    val commandRejected: String
    val notConnected: String
    val abortSent: String
    val agentWorking: String
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
    val turnStart: String
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

    // misc
    val back: String
    val justNow: String
    val minutesAgo: (Int) -> String
    val hoursAgo: (Int) -> String
    val daysAgo: (Int) -> String
}

object EnStrings : Strings {
    override val appName = "Pi"
    override val appTagline = "Your coding agent, anywhere"
    override val welcomeTitle = "Welcome to Pi"
    override val welcomeBody = "Connect to a pi2ws gateway to chat with your pi coding agent — watch it think, call tools and write code in real time."
    override val serverSetupTitle = "Set up your first server"
    override val serverNameLabel = "Name"
    override val serverUrlLabel = "Server address"
    override val serverTokenLabel = "Token"
    override val serverNamePlaceholder = "My workstation"
    override val serverUrlPlaceholder = "ws://192.168.1.10:8080"
    override val serverTokenPlaceholder = "PI2WS_TOKEN"
    override val connectAndSave = "Save & Connect"
    override val serverUrlInvalid = "Address must start with ws://, wss://, http:// or https://"
    override val serverRequired = "Server address is required"

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
    override val deleteSessionBody = "This only removes the chat from this device. The session stays on the server."
    override val untitledSession = "Untitled chat"

    override val workspaceDialogTitle = "Choose a workspace"
    override val workspaceLabel = "Workspace"
    override val workspaceHint = "Absolute path"
    override val defaultWorkspace = "Default workspace"
    override val selectThisDirectory = "Select this directory"
    override val upLevel = "Up"
    override val noSubdirectories = "No subdirectories"

    override val messagePlaceholder = "Message Pi…"
    override val messagePlaceholderStreaming = "Steer the agent…"
    override val send = "Send"
    override val stop = "Stop"
    override val thinking = "Thinking"
    override val thinkingInProgress = "Thinking…"
    override val toolRunning = "Running"
    override val toolInput = "Input"
    override val toolOutput = "Output"
    override val connected = "Online"
    override val connecting = "Connecting"
    override val disconnected = "Offline"
    override val connectionError = "Connection failed"
    override val reconnect = "Reconnect"
    override val model = "Model"
    override val thinkingLevel = "Thinking"
    override val noModel = "Default"
    override val selectModel = "Select model"
    override val selectThinkingLevel = "Thinking effort"
    override val newSessionCreated = "New chat created"
    override val sessionAttached = "Session restored"
    override val piCrashed = "The pi process exited unexpectedly. Reconnect to resume."
    override val connectionClosed = "Connection closed"
    override val commandRejected = "Command rejected"
    override val notConnected = "Not connected"
    override val abortSent = "Stop requested"
    override val agentWorking = "Working"
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
    override val turnStart = "New turn"
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
    override val deleteServerBody = "Sessions remembered for this server are removed too."
    override val appearanceSection = "Appearance"
    override val themeSystem = "System"
    override val themeLight = "Light"
    override val themeDark = "Dark"
    override val languageSection = "Language"
    override val languageSystem = "System"
    override val aboutSection = "About"
    override val activeServerHint = "Active"

    override val back = "Back"
    override val justNow = "just now"
    override val minutesAgo = { m: Int -> "${m}m ago" }
    override val hoursAgo = { h: Int -> "${h}h ago" }
    override val daysAgo = { d: Int -> "${d}d ago" }
}

object ZhStrings : Strings {
    override val appName = "Pi"
    override val appTagline = "随身携带的编码智能体"
    override val welcomeTitle = "欢迎使用 Pi"
    override val welcomeBody = "连接 pi2ws 网关，与你的 pi 编码智能体对话——实时查看它的思考、工具调用与代码编写过程。"
    override val serverSetupTitle = "配置第一台服务器"
    override val serverNameLabel = "名称"
    override val serverUrlLabel = "服务器地址"
    override val serverTokenLabel = "令牌"
    override val serverNamePlaceholder = "我的工作站"
    override val serverUrlPlaceholder = "ws://192.168.1.10:8080"
    override val serverTokenPlaceholder = "PI2WS_TOKEN"
    override val connectAndSave = "保存并连接"
    override val serverUrlInvalid = "地址需以 ws://、wss://、http:// 或 https:// 开头"
    override val serverRequired = "服务器地址不能为空"

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
    override val deleteSessionBody = "仅从此设备移除记录，服务器上的会话仍会保留。"
    override val untitledSession = "未命名会话"

    override val workspaceDialogTitle = "选择工作区"
    override val workspaceLabel = "工作区"
    override val workspaceHint = "绝对路径"
    override val defaultWorkspace = "默认工作区"
    override val selectThisDirectory = "选择此目录"
    override val upLevel = "上一级"
    override val noSubdirectories = "没有子目录"

    override val messagePlaceholder = "给 Pi 发送消息…"
    override val messagePlaceholderStreaming = "引导智能体…"
    override val send = "发送"
    override val stop = "停止"
    override val thinking = "思考过程"
    override val thinkingInProgress = "正在思考…"
    override val toolRunning = "运行中"
    override val toolInput = "输入"
    override val toolOutput = "输出"
    override val connected = "在线"
    override val connecting = "连接中"
    override val disconnected = "离线"
    override val connectionError = "连接失败"
    override val reconnect = "重新连接"
    override val model = "模型"
    override val thinkingLevel = "思考强度"
    override val noModel = "默认"
    override val selectModel = "选择模型"
    override val selectThinkingLevel = "思考强度"
    override val newSessionCreated = "已创建新会话"
    override val sessionAttached = "已恢复历史会话"
    override val piCrashed = "pi 进程异常退出，重新连接可自动恢复。"
    override val connectionClosed = "连接已关闭"
    override val commandRejected = "命令被拒绝"
    override val notConnected = "尚未连接"
    override val abortSent = "已发送中止请求"
    override val agentWorking = "运行中"
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
    override val turnStart = "新一轮开始"
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
    override val deleteServerBody = "该服务器下已记住的会话记录也会一并移除。"
    override val appearanceSection = "外观"
    override val themeSystem = "跟随系统"
    override val themeLight = "浅色"
    override val themeDark = "深色"
    override val languageSection = "语言"
    override val languageSystem = "跟随系统"
    override val aboutSection = "关于"
    override val activeServerHint = "当前"

    override val back = "返回"
    override val justNow = "刚刚"
    override val minutesAgo = { m: Int -> "${m} 分钟前" }
    override val hoursAgo = { h: Int -> "${h} 小时前" }
    override val daysAgo = { d: Int -> "${d} 天前" }
}

val LocalStrings = staticCompositionLocalOf<Strings> { EnStrings }

/** Current language strings, provided by [io.github.yearsyan.pi.App]. */
val S: Strings
    @Composable get() = LocalStrings.current

fun stringsFor(language: AppLanguage, systemIsChinese: Boolean): Strings = when (language) {
    AppLanguage.English -> EnStrings
    AppLanguage.Chinese -> ZhStrings
    AppLanguage.System -> if (systemIsChinese) ZhStrings else EnStrings
}
