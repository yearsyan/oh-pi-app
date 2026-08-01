# Pi — pi2ws Compose Multiplatform 客户端

基于 pi2ws 网关的聊天客户端，面向 Android / Desktop (JVM) / iOS（共享代码已兼容，未单独验证）。
UI 风格参考 DeepSeek / ChatGPT / Codex 等 AI 聊天应用。

## 功能

- **会话列表 / 会话详情**：本地持久化每个服务器下的会话，支持新建、恢复（attach 后通过
  `get_entries` 重建时间线）、重命名（同步 `set_session_name`）、删除。
- **会话内对话**：流式渲染助手消息（Markdown：标题/列表/引用/代码块/行内样式），
  运行中可随时中止（abort）或引导（steer）。
- **思考过程**：可折叠的思考卡片，流式更新。
- **工具调用**：工具卡片展示名称、参数与输出，运行/完成/错误状态实时更新。
- **模型与思考强度切换**：顶栏下拉选择（`get_available_models` / `set_model`，
  `get_available_thinking_levels` / `set_thinking_level`），失败自动回读状态。
- **自适应布局**：宽度 ≥ 840dp（平板/桌面）为「列表 + 详情」双栏；手机为单栏导航。
- **多语言**：中文 / English / 跟随系统。
- **深色 / 浅色**：跟随系统或手动指定。
- **多服务器**：设置页维护多个 pi2ws 网关（名称 / 地址 / token），随时切换；
  首次启动未配置时进入引导配置页。
- **扩展 UI 对话框**：支持 pi 扩展的 select / confirm / input / editor 请求。

## 架构（shared 模块）

```
data/        ServerProfile、SavedSession、SettingsStore（multiplatform-settings 持久化）
net/         PiClient（Ktor CIO WebSocket，事件回主线程）、协议 JSON 工具
chat/        ChatController（连接 + pi RPC 状态机 + 时间线归约）、Timeline 模型
markdown/    轻量 Markdown 渲染器
i18n/        中英文字符串表
theme/       Material3 深浅色主题 + 语义扩展色
ui/          AppViewModel（导航/服务器/会话/主题）、screens、components
```

协议细节见仓库根目录 `README.md` 的 WebSocket API 一节；连接与会话语义与 `web-demo` 一致。

## 运行

- Android：`./gradlew :androidApp:assembleDebug`，安装 `androidApp/build/outputs/apk/debug/`。
  真机连接本机网关时，网关需监听 `0.0.0.0` 并在 App 内填写局域网地址，例如
  `PI2WS_TOKEN=xxx ./bin/pi2ws --listen 0.0.0.0:18080 --allow-origin='*'`。
- Desktop：`./gradlew :desktopApp:run`。
- iOS：使用 `/iosApp` 工程入口（未单独验证）。

## 备注

- token 仅保存在本机设置中，不会写入日志。
- 会话列表保存在本地（网关无列表 API）；删除会话只移除本地记录。
