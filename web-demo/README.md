# pi2ws Web Demo

基于 Vue 3 + Vite 的浏览器端演示：通过 pi2ws 网关与 pi coding agent 对话，实时展示思考过程、工具调用与执行输出，交互风格类似 Codex App。

## 功能

- **会话管理**：新建会话、恢复历史会话（会话列表保存在浏览器 localStorage，attach 后通过 `get_entries` 回放完整历史）。
- **流式对话**：`message_update` 增量渲染文本与思考内容，支持多内容块顺序排列。
- **思考过程**：`thinking_*` delta 实时流入可折叠的"思考过程"卡片。
- **工具调用**：`toolcall_*` 流式渲染参数 JSON；`tool_execution_*` 实时显示执行状态与输出（进行中 / 完成 / 失败），支持展开查看与复制。
- **运行控制**：Agent 运行中显示"停止"按钮（`abort`）；运行期间发送的消息自动带 `streamingBehavior: "steer"` 排队插入。
- **扩展 UI**：支持 `ctx.ui.select / confirm / input / editor` 对话框与 `notify / setStatus / setTitle` 通知。
- **模型 / 思考强度切换**：顶栏下拉菜单展示 `get_available_models` 全部模型（按 provider 分组）与 `get_available_thinking_levels` 级别，点击即发送 `set_model` / `set_thinking_level`（乐观更新，失败自动回读）。
- **状态提示**：`agent_start / turn_start / agent_end / agent_settled / compaction / auto_retry` 等事件以状态条展示，队列中的 steer/follow-up 消息以标签展示。

## 运行

```bash
cd web-demo
npm install        # 或 pnpm install / yarn
npm run dev        # http://localhost:5173
```

构建产物：

```bash
npm run build      # 输出到 dist/
npm run preview    # 本地预览构建产物
```

## 连接配置

开发时 **无需任何配置**：Vite 开发服务器已将 `/ws` 反向代理到 `http://127.0.0.1:8080`（`vite.config.ts`），浏览器连同源的 `ws://localhost:5173/ws` 即可，不涉及跨域，网关也不需要 `--allow-origin`。

点击左下角"设置"可覆盖默认配置：

- **网关地址**：留空 = 同源 `/ws` 代理（推荐）；或填写直连地址，如 `ws://127.0.0.1:8080`（也接受 `http://` 前缀，会自动转 `ws://`）。
- **Token**：网关的 `PI2WS_TOKEN`。

配置保存在浏览器 localStorage。连接 URL 为：

```
ws://<gateway>/ws?action=create|attach&session_id=<id>&token=<token>
```

若直连（不走代理），浏览器 Origin 必须被网关允许，例如 `./bin/pi2ws --allow-origin=http://localhost:5173`。

## 与网关的对应关系

| 界面 | 网关 / pi RPC |
|---|---|
| 新建会话 | `action=create` 连接 |
| 恢复会话 | `action=attach&session_id=...` + `get_entries` |
| 发送 / 排队消息 | `prompt`（运行中自动加 `streamingBehavior: "steer"`） |
| 停止 | `abort` |
| 思考 / 文本 | `message_update` 的 `thinking_*` / `text_*` delta |
| 工具卡片 | `toolcall_*` + `tool_execution_*` |
| 对话框 | `extension_ui_request` ↔ `extension_ui_response` |

## 目录结构

```text
src/
├── App.vue                  # 布局：侧栏 + 消息区 + 输入框
├── components/
│   ├── Sidebar.vue          # 会话列表 / 新建 / 设置
│   ├── MessageItem.vue      # 用户、助手、工具、状态消息
│   ├── ToolCallCard.vue     # 工具调用卡片（参数 / 输出 / 状态）
│   ├── Composer.vue         # 输入框 + 发送 / 停止
│   ├── ExtensionDialog.vue  # 扩展 UI 对话框
│   └── SettingsModal.vue    # 网关与 token 配置
└── lib/
    ├── types.ts             # 协议与视图模型类型
    ├── ws.ts                # WebSocket 客户端封装
    ├── store.ts             # 事件状态机（事件 → 时间线）
    └── markdown.ts          # 轻量安全 Markdown 渲染
```
