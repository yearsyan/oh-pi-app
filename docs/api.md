# API 与 WebSocket 协议

ohpi-gateway 使用 HTTP API 管理持久化 session 和浏览远程文件，使用 WebSocket 收发 pi RPC 与实时事件。进程和历史存储原理见[架构与持久化](architecture.md)。

## 鉴权

`GET /healthz` 不需要鉴权。其他 HTTP API 都推荐使用 Bearer 请求头，避免 token 出现在 URL 和访问日志中：

健康检查成功时返回网关版本与安装模式兼容协议版本。`os` 为网关宿主的 Go `runtime.GOOS`（如 `darwin`、`linux`、`windows`），旧版本网关不含该字段：

```json
{"status":"ok","service":"ohpi-gateway","version":"2.2.3","protocol":3,"os":"darwin","features":["workspaces_v2","session_process_stop","workspace_delete_v1","workspace_resources_v1","scheduled_tasks_v1","scheduled_task_skills_v1","scheduled_session_management_v1","scheduled_task_sessions_v1","runtime_config_v1"]}
```

```http
Authorization: Bearer <TOKEN>
```

WebSocket 连接通过 `token` query 参数鉴权。生产环境务必使用 `wss://` 并关闭或脱敏反向代理的 query 日志。

## 网关运行配置与重启

带有 `runtime_config_v1` feature 的网关允许可信客户端读取和修改下次启动生效的配置：

```http
GET /api/runtime-config
Authorization: Bearer <TOKEN>
```

```json
{
  "title_model": "auto",
  "pi_env_file": "~/.zshrc",
  "pi_env_shell": "/bin/zsh",
  "scheduled_session_retention_seconds": 604800,
  "restart_required": false,
  "restart_supported": true
}
```

`PATCH` 可以提交其中任意配置字段；空的 `pi_env_file` 会同时移除 shell 配置并禁用环境加载。`scheduled_session_retention_seconds` 是定时任务会话的闲置保留期，默认 604800 秒（7 天），允许 3600 秒到 10 年。网关会验证 title model、环境文件、shell 和保留期，原配置文件中的监听地址、数据目录等其他字段保持不变：

```http
PATCH /api/runtime-config
Authorization: Bearer <TOKEN>
Content-Type: application/json

{"title_model":"active","pi_env_file":"~/.bashrc","pi_env_shell":"/bin/bash","scheduled_session_retention_seconds":604800}
```

保存不会打断当前会话。保留期与其他运行配置一样在下次启动生效；响应中的 `restart_required` 为 `true` 时可请求优雅重启：

```http
POST /api/runtime-restart
Authorization: Bearer <TOKEN>
```

接口返回 HTTP 202 后，网关停止 pi 子进程和 HTTP 服务并以非零重启码退出；LaunchAgent、systemd user service 或 App 安装的计划任务会重新拉起它。未由进程管理器托管时，进程只会退出，不会自行派生替代进程。重启进行中 `/healthz` 返回 HTTP 503，直到新进程就绪。

环境文件以网关用户权限执行，因此这些接口应视为远程代码执行级管理能力，只允许持有私密 Bearer token 的可信客户端访问。

## 工作空间与会话 HTTP API

自协议 2 起由服务端维护工作空间。当前一个工作空间对应一个已存在的绝对目录；同一规范化目录只会有一个工作空间 ID。旧的 `/api/sessions` 和 `/api/capabilities` 已删除，不提供兼容层。

### 创建和列出工作空间

```http
POST /api/workspaces
Authorization: Bearer <TOKEN>
Content-Type: application/json

{"directory":"/path/to/project"}
```

首次注册返回 HTTP 201；目录已经注册时返回 HTTP 200 和原工作空间。目录会经过清理和 symlink 解析。重新注册已删除的目录也返回 HTTP 201，并恢复原工作空间 ID、元信息和全部会话。

```http
GET /api/workspaces?session_limit=5&include_scheduled=false
Authorization: Bearer <TOKEN>
```

`include_scheduled` 可选，默认为 `true` 以保持兼容；设为 `false` 时不返回由定时任务创建的会话。过滤会先于 `session_limit`、`session_count`、工作空间活跃度和分页计算。每个工作空间只内嵌最前面的 `session_limit` 条会话。含运行中会话的工作空间位于最前；工作空间内会话按 `running`、`outputting`、`last_active` 排序。时间戳单位为 Unix 毫秒：

```json
{
  "workspaces": [
    {
      "id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
      "directory": "/path/to/project",
      "name": "网关项目",
      "additional_system_prompt": "提交前运行测试。",
      "skill_paths": ["skills/team", "/srv/shared-skills"],
      "no_skills": true,
      "extension_paths": ["extensions/team.ts"],
      "no_extensions": true,
      "technology": "go",
      "technologies": ["go", "typescript", "javascript"],
      "session_count": 27,
      "sessions": [
        {
          "id": "6d2f8177-d1b5-43ce-927f-250666646e07",
          "name": "检查登录流程",
          "created_at": 1785736800000,
          "last_active": 1785738600000,
          "running": true,
          "outputting": false,
          "source": "scheduled_task",
          "scheduled_task_id": "a73ec34b-691d-4a76-ac6f-b5b191082757"
        }
      ],
      "next_cursor": "NQ",
      "created_at": 1785730000000,
      "updated_at": 1785739000000
    }
  ]
}
```

`technology` 是用于展示的最高优先级技术栈；`technologies` 保留全部探测结果。框架和构建工具优先于通用语言，例如 Next.js、Nuxt、Svelte、Angular、Vite、Vue、React、Flutter；之后再判断 Rust、Go、Kotlin、Swift、.NET、Python、TypeScript、JavaScript、PHP、Ruby、Elixir、Dart、C++ 和 Java。探测目前只检查工作空间根目录。

只有定时任务会话返回 `source: "scheduled_task"`；新版本同时返回稳定的 `scheduled_task_id`，普通交互会话省略这两个字段。带有 `scheduled_session_management_v1` feature 的网关支持来源字段和 `include_scheduled` 过滤；`scheduled_task_sessions_v1` 表示支持任务关联 ID 与下述关联会话接口。

### 工作空间元信息

```http
GET /api/workspaces/<WORKSPACE_ID>

PATCH /api/workspaces/<WORKSPACE_ID>
Content-Type: application/json

{
  "name": "网关项目",
  "additional_system_prompt": "提交前运行测试。",
  "skill_paths": ["skills/team", "/srv/shared-skills"],
  "no_skills": true,
  "extension_paths": ["extensions/team.ts", "/srv/extensions"],
  "no_extensions": true
}
```

所有 PATCH 字段均可单独提交。`name` 最长 200 个 Unicode 字符；`additional_system_prompt` 最长 64 KiB。`skill_paths` 和 `extension_paths` 各自最多包含 32 个非空条目，每项最长 4 KiB；重复项会按首次出现的位置去重。相对路径以工作空间目录为起点，绝对路径保持不变。

带有 `workspace_resources_v1` feature 的网关支持工作空间级 Pi 资源配置：

- `skill_paths` 按顺序转换为可重复的 `--skill <path>`；`no_skills=true` 同时传入 `--no-skills`，关闭自动发现，但以上显式路径仍会加载。
- `extension_paths` 按顺序转换为可重复的 `--extension <path>`；`no_extensions=true` 同时传入 `--no-extensions`，关闭自动发现，但以上显式路径以及网关自身显式 Extension 仍会加载。
- Extension 以网关用户的完整权限运行，只应配置可信文件或目录。

这些元信息保存在服务端，并在该工作空间新启动或恢复 Pi 进程时传入；工作空间的 `capabilities` 探测和定时任务创建的 Session 使用同一组参数。已在运行的进程不会被中途修改。

### 删除工作空间

带有 `workspace_delete_v1` feature 的网关支持删除工作空间注册：

```http
DELETE /api/workspaces/<WORKSPACE_ID>
Authorization: Bearer <TOKEN>
```

成功返回 HTTP 204。删除后工作空间不会出现在列表中，其 HTTP API 和新 WebSocket attach 返回 404；已经建立的会话连接不会被强行中断。该操作不会删除任何 session、聊天历史或工作空间目录文件。再次 `POST /api/workspaces` 注册同一规范化目录时，网关会恢复原工作空间 ID、元信息及全部会话。网关启动时对默认 `--work-dir` 的自动注册不会意外恢复一个已删除的工作空间。

### 分页加载工作空间会话

```http
GET /api/workspaces/<WORKSPACE_ID>/sessions?limit=20&cursor=<NEXT_CURSOR>&include_scheduled=false
Authorization: Bearer <TOKEN>
```

`include_scheduled` 的含义和默认值与工作空间列表相同。`cursor` 是服务端提供的不透明字符串，不应自行解析；切换过滤值后必须丢弃旧 cursor 并从第一页重新加载。响应如下；没有下一页时省略或返回空 `next_cursor`：

```json
{
  "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
  "sessions": [],
  "next_cursor": "MjU"
}
```

`running` 表示网关当前持有该会话的 pi 子进程，不表示存在 WebSocket 客户端。`outputting` 表示进程处于 `agent_start` 到 `agent_settled` 之间；它为 `true` 时 `running` 也一定为 `true`。

### 查询、重命名和删除会话

```http
GET /api/workspaces/<WORKSPACE_ID>/sessions/<SESSION_ID>

PATCH /api/workspaces/<WORKSPACE_ID>/sessions/<SESSION_ID>
Content-Type: application/json

{"name":"新的会话名称"}

DELETE /api/workspaces/<WORKSPACE_ID>/sessions/<SESSION_ID>
```

会话 ID 必须属于路径中的工作空间。名称会去除首尾空白，不能为空，最长 200 个 Unicode 字符。永久删除会先关闭活动进程和 WebSocket，再删除完整 session 目录；成功返回 HTTP 204。

### 仅停止会话的 pi 进程

```http
DELETE /api/workspaces/<WORKSPACE_ID>/sessions/<SESSION_ID>/process
Authorization: Bearer <TOKEN>
```

该操作保留完整会话和历史；下一次 attach 会在对应工作空间重新启动 pi。没有运行中进程时也返回 HTTP 204。当 `outputting: true` 时返回 HTTP 409 `session_outputting`：客户端必须先通过 WebSocket 发送 `abort`，等待 `agent_settled` 后再停止。

### 查询会话生成性能

```http
GET /api/workspaces/<WORKSPACE_ID>/sessions/<SESSION_ID>/metrics
Authorization: Bearer <TOKEN>
```

该接口返回网关从 pi 实时事件观测并持久化的 `sample_count`、`total_output_tokens`、`total_generation_ms`、`average_tps` 和 `average_ttft_ms`。查询不要求 pi 进程正在运行；无有效样本时两个平均值为 `null`。

### 查询新会话能力

```http
GET /api/workspaces/<WORKSPACE_ID>/capabilities
Authorization: Bearer <TOKEN>
```

网关在工作空间目录中启动短生命周期的 `pi --mode rpc --no-session`，探测 provider、模型、思考强度和扩展指令，但不创建 session。结果按工作空间目录短期缓存：

```json
{
  "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
  "directory": "/path/to/project",
  "default": {
    "provider": "openai",
    "model_id": "gpt-5",
    "thinking_level": "medium"
  },
  "models": [],
  "commands": []
}
```

思考强度是模型级能力，客户端切换模型时应使用该模型自己的 `thinking_levels`。`commands` 来自 pi 的 `get_commands`，包含当前工作空间可用的 extension、prompt template 和 skill 指令。

## 定时任务 HTTP API

带有 `scheduled_tasks_v1` feature 的网关提供服务端持久化调度。任务是网关级资源，不从属于某一个 API 路径中的工作空间，因为编辑时可以更换工作空间。所有接口均需 Bearer token。

支持三种 `schedule`：

- `cron`：标准五段式 `分钟 小时 日期 月份 星期`，精确到分钟；必须同时提供 IANA 时区，如 `Asia/Shanghai`。不接受秒或年份字段。
- `interval`：从 `anchor_at` 锚点按 `every_seconds` 固定节拍运行，最小间隔 60 秒。
- `once`：在未来的 RFC 3339 `at` 时间执行一次，领取执行后自动停用。

### 创建和列出任务

```http
POST /api/tasks
Authorization: Bearer <TOKEN>
Content-Type: application/json

{
  "name": "工作日项目检查",
  "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
  "model": "openai-codex/gpt-5.5",
  "thinking": "medium",
  "skill_paths": [".pi/task-skills", "/srv/shared-skills/release"],
  "no_skills": true,
  "prompt": "运行测试，修复明确的失败并总结结果。",
  "schedule": {
    "kind": "cron",
    "expression": "0 9 * * 1-5",
    "timezone": "Asia/Shanghai"
  },
  "enabled": true
}
```

`model` 和 `thinking` 可留空以使用工作空间默认值。带有 `scheduled_task_skills_v1` feature 的网关还接受多个 `skill_paths`；相对路径以任务的工作空间目录为起点，并与工作空间显式 Skills 路径合并。`no_skills` 对应 Pi 的 `--no-skills`，只关闭自动发现，工作空间和任务中显式配置的路径仍会加载。模型格式与 WebSocket create 的 `model` 参数一致；工作空间、模型和 Skills 配置会在保存时验证。创建成功返回 HTTP 201。

```http
GET /api/tasks
Authorization: Bearer <TOKEN>
```

响应按下次执行时间、名称和 ID 排序：

```json
{
  "tasks": [
    {
      "id": "a73ec34b-691d-4a76-ac6f-b5b191082757",
      "name": "工作日项目检查",
      "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
      "model": "openai-codex/gpt-5.5",
      "thinking": "medium",
      "skill_paths": [".pi/task-skills", "/srv/shared-skills/release"],
      "no_skills": true,
      "prompt": "运行测试，修复明确的失败并总结结果。",
      "schedule": {
        "kind": "cron",
        "expression": "0 9 * * 1-5",
        "timezone": "Asia/Shanghai"
      },
      "enabled": true,
      "next_run_at": "2026-08-13T01:00:00Z",
      "last_run": {
        "id": "e177689f-3fa0-432c-aac1-7c6150dac163",
        "scheduled_for": "2026-08-12T01:00:00Z",
        "started_at": "2026-08-12T01:00:01Z",
        "finished_at": "2026-08-12T01:03:20Z",
        "status": "succeeded",
        "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07"
      },
      "created_at": "2026-08-11T08:00:00Z",
      "updated_at": "2026-08-12T01:03:20Z"
    }
  ]
}
```

执行期间返回 `current_run`，结束后转为 `last_run`。状态可为 `running`、`succeeded`、`failed`、`interrupted` 或 `skipped`；失败原因在 `error` 中。任务 session 与普通 session 一样持久化，可以在 App 会话列表中打开；其会话元数据包含 `source: "scheduled_task"`，因此列表可以单独过滤。

### 查询、编辑、删除和立即执行

```http
GET /api/tasks/<TASK_ID>

PATCH /api/tasks/<TASK_ID>
Content-Type: application/json

{"enabled":false}

DELETE /api/tasks/<TASK_ID>

POST /api/tasks/<TASK_ID>/run
```

PATCH 可提交任意任务字段；App 编辑页提交完整定义。修改启用中的空闲任务会从当前时间重新计算下次执行。任务运行期间 PATCH 和 DELETE 均返回 HTTP 409；删除任务不会删除它已经创建的 session。

立即执行返回 HTTP 202，允许在任务暂停时使用，且不改变原计划的 `next_run_at`。同一任务不会并发执行；上一次仍在运行时，立即执行返回 HTTP 409，计划 occurrence 则跳过并推进到下一次。

### 分页查询任务关联会话

带有 `scheduled_task_sessions_v1` feature 的网关支持查询一个任务历次运行产生且仍在保留期内的全部会话：

```http
GET /api/tasks/<TASK_ID>/sessions?limit=20&cursor=<NEXT_CURSOR>
Authorization: Bearer <TOKEN>
```

会话按运行状态和最近活跃时间排序。任务后来更换工作空间不会改变已有会话的关联；响应为每条会话补充其历史工作空间信息。已删除的工作空间仍会列出并标记 `workspace_deleted`，但恢复该工作空间前不能 attach：

```json
{
  "task_id": "a73ec34b-691d-4a76-ac6f-b5b191082757",
  "session_count": 2,
  "sessions": [
    {
      "id": "6d2f8177-d1b5-43ce-927f-250666646e07",
      "name": "[定时] 工作日项目检查",
      "created_at": 1785736800000,
      "last_active": 1785738600000,
      "running": false,
      "outputting": false,
      "source": "scheduled_task",
      "scheduled_task_id": "a73ec34b-691d-4a76-ac6f-b5b191082757",
      "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
      "workspace_directory": "/path/to/project",
      "workspace_name": "网关项目"
    }
  ],
  "next_cursor": "MQ"
}
```

删除任务后该任务资源及此查询入口消失，但不会删除已经产生的会话。升级时网关会为任务元数据中仍可追溯的 `current_run` 和 `last_run` 会话补写关联；更早由旧版网关产生且没有持久关联 ID 的会话无法可靠反向归属。

网关停机期间积压多个 Cron/间隔 occurrence 时，恢复后至多补执行一次并直接推进到未来的下一次；单次任务仍会补执行一次。任务在执行前先持久化领取并推进计划，因此网关崩溃后的不确定执行不会自动重复，恢复时记为 `interrupted`。删除工作空间会自动暂停关联任务。

带有 `scheduled_session_management_v1` feature 的网关会在启动后立即、此后每小时清理一次超过保留期的定时任务会话。保留期按会话 `last_active` 计算，默认 7 天；正在运行的会话不会被清理，打开会话或继续对话会刷新活跃时间。普通交互会话不受此自动清理影响。最近版本升级时，网关会根据任务的 `current_run` / `last_run` 标记能够识别的旧任务会话。

## pi 内置 Provider 管理

这些接口只管理 pi 自带 catalog 中的 Provider。`models.json` 新增的自定义 Provider 和
extension Provider 不会出现在管理清单中，也不能通过这些接口登录或登出；它们仍由 pi
照常加载，提供的模型也仍会出现在工作空间的 `capabilities` 接口和会话模型选择器中。

### 列出 Provider 与模型

```http
GET /api/providers
Authorization: Bearer <TOKEN>
```

响应只包含非敏感元数据，不返回 API key、access token 或 refresh token：

```json
{
  "providers": [
    {
      "id": "openai-codex",
      "name": "OpenAI Codex",
      "configured": true,
      "auth_source": "stored",
      "auth_label": "OAuth",
      "stored_auth_type": "oauth",
      "auth_methods": [
        { "type": "oauth", "name": "OpenAI", "label": "Sign in with ChatGPT" }
      ],
      "models": [
        { "id": "gpt-5.5", "name": "GPT-5.5", "reasoning": true, "input": ["text", "image"] }
      ]
    }
  ]
}
```

`configured` 也可能由环境变量或配置文件提供；这种凭据没有可删除的
`stored_auth_type`。`auth_methods` 来自 Provider 自身，可包含 `api_key`、`oauth` 或两者。

### 登录或重新登录

登录使用独立 WebSocket，以便透传每个内置 Provider 自己的动态输入、选项、OAuth 和
设备码流程：

```text
wss://gateway.example/api/provider-auth?provider_id=openai-codex&auth_type=oauth&token=<TOKEN>
```

网关发送的消息统一使用 `{"type":"ohpi_provider"}`。主要事件为：

```json
{"type":"ohpi_provider","event":"ready","provider_id":"openai-codex","auth_type":"oauth"}
{"type":"ohpi_provider","event":"prompt","id":"p1","kind":"secret","message":"API key"}
{"type":"ohpi_provider","event":"auth_url","url":"https://…","instructions":"Continue in the browser"}
{"type":"ohpi_provider","event":"device_code","user_code":"ABCD-EFGH","verification_uri":"https://…"}
{"type":"ohpi_provider","event":"progress","message":"Waiting for authorization"}
{"type":"ohpi_provider","event":"complete","action":"login","provider_id":"openai-codex"}
```

`prompt.kind` 可为 `select` 或文本/密钥类提示；选项提示还带 `options` 和可选的
`descriptions`。客户端只需用以下受限消息回答当前提示，其他 pi RPC 不会被转发：

```json
{"type":"extension_ui_response","id":"p1","value":"<answer>"}
```

取消提示可发送 `{"type":"extension_ui_response","id":"p1","cancelled":true}`，随后关闭
WebSocket。OAuth 流程还可能发送 `info`（含 `links`）和 `error`。登录成功后网关使工作区
能力缓存失效，并让已稳定的 pi session 通过正常重连载入新凭据；进行中的模型调用不会
被打断。

### 登出

```http
DELETE /api/providers/<PROVIDER_ID>/credential
Authorization: Bearer <TOKEN>
```

该操作调用 pi 自身的 logout，并从 pi 的凭据存储中删除该 Provider 的已保存凭据；成功
返回 HTTP 204。环境变量提供的凭据不会因此从进程环境中消失。所有 Provider 辅助进程都
不会接收 `OHPI_TOKEN`，App 也不会把 Provider 凭据写入本地设置。

## 文件与目录 HTTP API

### 目录浏览（工作区选择）

```http
GET /fs/list?path=<绝对路径>
Authorization: Bearer <TOKEN>
```

该接口只列出目录下的子目录，不含文件，供客户端实现工作区选择器：

```json
{
  "path": "/path/to/project",
  "parent": "/path/to",
  "dirs": [
    { "name": "app", "path": "/path/to/project/app" },
    { "name": "cmd", "path": "/path/to/project/cmd" }
  ]
}
```

省略 `path` 时从网关的 `--work-dir` 开始浏览。`path` 必须是绝对路径；不存在的路径返回 404，无权限读取返回 403。

### 创建目录（工作区选择）

```http
POST /fs/mkdir
Authorization: Bearer <TOKEN>
Content-Type: application/json

{"parent":"/path/to/project","name":"new-folder"}
```

`parent` 必须是网关主机上已存在的绝对目录。`name` 只能是一个非空目录名，不能是 `.`、`..`，也不能包含 `/`、`\` 或 NUL；服务端不会接受由客户端拼接的目标路径。接口只创建一层目录，不会递归创建缺失的父目录。

成功时返回 HTTP 201 和创建后的目录：

```json
{"name":"new-folder","path":"/path/to/project/new-folder"}
```

父目录不存在时返回 404 `not_found`，名称或路径无效时返回 400 `invalid_name` / `invalid_path`，目标已存在时返回 409 `already_exists`，无法解析或无写权限时返回 403 `resolve_failed` / `mkdir_failed`。与目录浏览一致，该接口不限制在 `--work-dir` 下；鉴权调用方可在 ohpi 进程有权限访问的任意绝对目录中创建子目录。

### 文件浏览（远程文件管理）

`internal/filebrowser` 包提供完整的文件浏览 HTTP API。鉴权方式与 `/fs/list` 相同，所有接口仅支持 GET。

列出目录下的目录和文件，目录在前、按名称排序：

```http
GET /api/files/list?path=<绝对路径>
```

```json
{
  "path": "/path/to/project",
  "parent": "/path/to",
  "truncated": false,
  "entries": [
    { "name": "cmd", "path": "/path/to/project/cmd", "is_dir": true, "size": 96, "mod_time": 1754300000000 },
    { "name": "main.go", "path": "/path/to/project/main.go", "is_dir": false, "size": 1024, "mod_time": 1754300000000 }
  ]
}
```

读取文本文件内容，默认上限 1 MiB，超出时 `truncated: true`；含 NUL 字节的二进制文件返回 415 `binary_file`：

```http
GET /api/files/read?path=<绝对路径>
```

```json
{ "path": "/path/to/main.go", "name": "main.go", "size": 1024, "truncated": false, "content": "package main\n…" }
```

下载原始文件：

```http
GET /api/files/download?path=<绝对路径>
```

响应使用 `application/octet-stream` 和 `Content-Disposition: attachment`，支持 Range；Android 客户端用它下载 APK 后调起安装。

省略 `path` 时，`list` 从网关的 `--work-dir` 开始；`read` 和 `download` 必须显式给出 `path`。路径必须是绝对路径，服务端会做 symlink 解析；不存在的路径返回 404，目录传给 `read` 或 `download` 返回 400。

## WebSocket API

### 创建 session

连接时必须用服务端工作空间 ID：

```text
ws://127.0.0.1:18080/ws?action=create&token=<TOKEN>&workspace_id=e28a1f96-41c4-41df-b659-678d7dbc1e8c&model=openai/gpt-5&thinking=high
```

`workspace_id` 必须先通过 `/api/workspaces` 注册。省略或格式非法时在 WebSocket 升级前返回 HTTP 400；工作空间不存在时返回 HTTP 404。网关使用工作空间的规范化 `directory` 作为 pi 子进程目录。

可选的 `model` 必须使用 `provider/model-id` 格式。可选的 `thinking` 为 `off`、`minimal`、`low`、`medium`、`high`、`xhigh` 或 `max`。它们只允许用于 `action=create`，并在 pi 读取第一条 RPC 命令前作为该 session 的初始配置应用。

连接成功后的第一条消息由网关发送：

```json
{
  "type": "ohpi",
  "event": "ready",
  "action": "create",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07",
  "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
  "workspace_directory": "/path/to/project"
}
```

客户端可以立即使用 `session_id`；以后通过对应工作空间的 session 列表发现并恢复。session 元数据持久化 `workspace_id`，目录和追加系统提示词由工作空间统一管理。

### 连接历史 session

```text
ws://127.0.0.1:18080/ws?action=attach&session_id=<SESSION_ID>&entry_since=<LAST_ENTRY_ID>&replay_base=<STABLE_SEQ>&replay_since=<LAST_REPLAY_SEQ>&replay_cursor=1&token=<TOKEN>
```

`entry_since` 可省略。客户端应把已经完整提交到本地缓存的最后一个 entry ID 放在这里；网关只同步这个 entry 后面的稳定记录。如果游标不存在于当前稳定历史中，`history_begin.reset` 为 `true`，客户端必须丢弃该 session 的旧缓存并从头接收。

`replay_base` 与 `replay_since` 必须成对出现。前者是本地 replay 缓存所基于的稳定历史 `through_seq`，后者是本地已经完整提交的 replay 高水位。仅当 `entry_since`、`replay_base` 都与服务端当前稳定边界完全一致，且 `replay_since` 未超过服务端高水位时，网关才从该序号后继续；否则会安全地从当前稳定历史边界重新回放。因此客户端可以持久化仍在进行中的 turn，重连或重启后只补缺失尾部。

`replay_cursor=1` 表示客户端需要带序号的实时事件。可回放的 live pi 事件会封装为 `ohpi/live`，使客户端能把每条事件及其 `seq` 原子写入本地 replay 缓存。

attach 按以下顺序发送，最后才发送 `ohpi/ready`。收到 `ready` 表示历史、活动事件和实时广播之间的无缝切换已经完成，此时客户端才应发送 `get_state` 等 RPC：

1. `ohpi/history_begin`
2. 零到多个 WebSocket Binary 消息，内容是原始稳定历史 JSONL 字节
3. `ohpi/history_end`
4. `ohpi/replay_begin`
5. 零到多个 `ohpi/replay_event`；超大单事件则是一个 `ohpi/replay_binary_begin`，随后跟随多个 WebSocket Binary 消息
6. `ohpi/replay_end`
7. 一个 `ohpi/ui_request_snapshot`，随后是零到多个 `ohpi/ui_request_pending`，表示当前仍等待回答的交互请求
8. `ohpi/ready`
9. attach 高水位之后的实时 pi 事件

```json
{"type":"ohpi","event":"history_begin","reset":false,"entry_id":"entry-45","through_seq":41,"total_bytes":287104}
<WebSocket Binary: raw JSONL bytes>
{"type":"ohpi","event":"history_end","entry_id":"entry-45","through_seq":41}
{"type":"ohpi","event":"replay_begin","from_seq":42,"through_seq":45}
{"type":"ohpi","event":"replay_event","seq":42,"payload":{"type":"agent_start"},"total_bytes":22}
{"type":"ohpi","event":"replay_binary_begin","seq":43,"total_bytes":734003}
<WebSocket Binary: raw JSON event bytes>
{"type":"ohpi","event":"replay_end","through_seq":45}
{"type":"ohpi","event":"ui_request_snapshot"}
{"type":"ohpi","event":"ui_request_pending","request_id":"dialog-1","payload":{"type":"extension_ui_request","id":"dialog-1","method":"confirm","title":"继续？"}}
{
  "type": "ohpi",
  "event": "ready",
  "action": "attach",
  "session_id": "6d2f8177-d1b5-43ce-927f-250666646e07",
  "workspace_id": "e28a1f96-41c4-41df-b659-678d7dbc1e8c",
  "workspace_directory": "/path/to/project"
}
```

`history_begin` 后、`history_end` 前的 Binary 消息直接携带原始 JSONL 字节。客户端应按收到顺序追加到 staging 文件；累计值必须等于 `history_begin.total_bytes`，并且只能在收到 `history_end` 后原子提交 staging 文件和新的 `entry_id` 游标。中途断线或长度不符必须回滚。

`history_begin.total_bytes` 是本阶段的 JSONL 总字节数，可与客户端累计写入的 Binary 字节数计算恢复进度。值为零时字段可能省略。

不超过 256 KiB 的 replay 记录通过 `replay_event.payload` 直接携带 JSON 对象。超大单事件先发送 `replay_binary_begin` 元信息，再用最多 256 KiB 的 Binary 消息发送原始 JSON 字节；Binary 消息没有 Base64 封装，也不独立携带序号。客户端按当前 `seq` 顺序写入临时文件，累计达到 `total_bytes` 后立即完成该事件并解析 JSON；超出声明长度、在完成前收到其他文本消息或在 `replay_end` 时仍不完整都属于协议错误。结合 `replay_begin.from_seq` / `through_seq`，客户端可显示事件级和大事件字节级进度。

启用 `replay_cursor=1` 后，`ready` 之后的可回放事件格式如下。网关会删除 `message_update` 中不影响重建结果的累计 `message` 快照，并且除 `start` 外删除重复的 `partial`；`turn_end` 中与 `message_end` 相同的完整消息也会被删除：

```json
{"type":"ohpi","event":"live","seq":46,"payload":{"type":"message_update","assistantMessageEvent":{"type":"text_delta","contentIndex":0,"delta":"..."}}}
```

每个 Binary 消息最多携带 256 KiB 原始数据。因此稳定历史总量、单个稳定 entry 的大小、活动 turn 回放总量都不会被一个 WebSocket 消息截断。活动事件先以紧凑形式落到磁盘 WAL，attach 从磁盘持续追平；网关在同一序列化临界区内发送 `replay_end`、注册实时高水位，保证不会漏掉 replay 与 live 之间的事件。

replay 序号是单调高水位，不要求在磁盘中连续。网关在 assistant `message_end` 后只保留最终消息，在 `tool_execution_end` 后删除中间工具输出，并对 `queue_update`、会话名等状态采用 last-write-wins。被合并掉的序号不会造成缺口：带较旧 `replay_since` 的客户端会收到其后仍有效的最终状态，再由 `replay_end.through_seq` 提交新的高水位。启动恢复会用相同规则原子迁移旧 WAL。

### 交互式 extension UI

协议 3 将 `select`、`confirm`、`input`、`editor` 类型的 `extension_ui_request` 作为 session 级临时状态，而不是普通 replay 记录。实时客户端仍直接收到 pi 的原始请求；attach 客户端先以 `ohpi/ui_request_snapshot` 清空临时请求状态，再通过 `ohpi/ui_request_pending.payload` 恢复当前尚未回答的请求。客户端必须按请求 `id` 去重，并在收到 `ready` 后才展示快照中的请求。snapshot 即使为空也会发送，使客户端能够清除旧版本缓存中残留的交互事件。

多个客户端可以同时显示同一个请求，但只有第一份 `extension_ui_response` 会被转发给 pi。网关接受第一份答案后向该 session 的所有实时客户端广播：

```json
{"type":"ohpi","event":"ui_request_resolved","request_id":"dialog-1"}
```

所有客户端都应关闭相同 ID 的弹窗。较晚的答案不会再次转发，发送方收到 `ohpi/error`，错误码为 `ui_request_already_resolved`；未知或已经失效的 ID 返回 `ui_request_not_pending`。resolved 事件只包含请求 ID，不包含回答正文。

历史 session 必须由当前 `--data-dir` 对应的 ohpi 实例创建。不存在或格式非法的 ID 在 WebSocket 升级前返回 HTTP 404。

### 收发 pi RPC

收到 `ready` 后，每个 WebSocket 文本帧发送一个 pi RPC JSON 对象：

```json
{"id":"browser-a-1","type":"prompt","message":"请检查当前项目"}
```

网关把 pi 的响应和流式事件广播给这个 session 上的每一个 WebSocket：

```json
{"id":"browser-a-1","type":"response","command":"prompt","success":true}
```

对于带字符串 `id` 的 `prompt`、`steer` 和 `follow_up` 命令，网关会把该 ID 关联到随后由 pi 发出的 user `message_start` 与 `message_end`，并在事件顶层增加 `source_id`：

```json
{"type":"message_start","source_id":"browser-a-1","message":{"role":"user","content":"请检查当前项目"}}
```

关联后的事件会先写入 replay WAL 再广播，因此活动 turn 重连回放时仍保留同一个 `source_id`。命令没有字符串 `id` 时保持兼容，事件不会增加 `source_id`。客户端应使用不可复用的 ID，并以收到匹配 `source_id` 的 user 事件作为输入已进入 session 的确认。

skill、prompt template 会展开输入文本，extension 指令也可能不产生 user 事件。此类 slash prompt 可额外发送 `"ohpi_confirm_on_response":true`；网关会在转发给 pi 前移除该字段，并在成功的 prompt response 到达时释放关联状态。客户端应以该 response 作为输入确认。

除上述 user 事件关联字段外，pi 的 `agent_start`、`message_update`、`tool_execution_*`、`agent_end` 等事件保持原协议。完整命令和事件格式以本机 pi 的 `docs/rpc.md` 为准。

标题 extension 或手工重命名成功时，pi 会发送原生事件；客户端应使用它更新当前标题和 session 列表，而不是依赖 `set_session_name` response 中不存在的名称字段：

```json
{"type":"session_info_changed","name":"排查登录回调"}
```

注意：

- 输出是 session 级广播，不是按发送者私有路由。多个客户端必须给会产生用户消息的 RPC `id` 加各自的唯一前缀，避免 `source_id` 冲突。
- attach 从 pi 的 append-only session JSONL 按 entry 游标发送稳定历史，并从磁盘 WAL 发送当前活动 turn；不再调用或内嵌整包 `get_entries`。客户端仍可在 `ready` 后主动发送其他 pi RPC。
- 普通 pi 事件会进入活动 turn WAL；交互式 `extension_ui_request` 由网关以 pending 快照恢复，RPC `response` 不回放，避免 attach 客户端误处理旧请求或并非由它发起的命令响应。
- `abort`、`steer` 等命令会影响整个共享 session。
- `new_session`、`switch_session`、`fork`、`clone` 会破坏网关的 session 与子进程映射，因此会被网关拒绝。新 session 应通过新的 `action=create` 连接创建。
- 格式错误的命令只会向发送方返回 `ohpi/error`，不会转发给 pi。

浏览器端最小示例：

```js
const ws = new WebSocket(
  "ws://127.0.0.1:18080/ws?action=create&token=" +
    encodeURIComponent(token) +
    "&workspace_id=" + encodeURIComponent(workspaceId),
);

ws.onmessage = ({ data }) => {
  const message = JSON.parse(data);
  console.log(message);

  if (message.type === "ohpi" && message.event === "ready") {
    localStorage.setItem("piSessionId", message.session_id);
    ws.send(JSON.stringify({
      id: crypto.randomUUID(),
      type: "get_state",
    }));
  }
};
```
