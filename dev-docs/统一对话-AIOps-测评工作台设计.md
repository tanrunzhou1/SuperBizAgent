# 统一对话、AIOps 与测评工作台设计

## 1. 文档目的

本文描述普通对话、线上 AIOps 排查和 evaluations 测评的统一 Agent 配置与工作台设计。三种能力复用同一个大模型连接配置和 ReAct 执行方式，但各自使用独立 Agent 配置：提示词、采样参数和工具集合彼此隔离。普通对话与线上 AIOps 共用聊天 session；Evaluations 独立运行，测评请求和结果不进入聊天记录或普通对话上下文。

这是面向后续实现的设计稿，不代表本文提到的改动已经实现。

## 2. 现状与差距

仓库当前具备以下基础：

- `ChatService` 按 `ai.model` 配置创建 Responses API ChatModel，聊天使用 `createStandardChatModel()`。
- AIOps 通过 `AiOpsRunService`、`AiOpsService` 运行 ReAct Agent；线上 LIVE 与离线 REPLAY 共用诊断流程，分别装配实时工具和 Cloud-OpsBench 回放工具。
- `ChatSessionService` 将 USER/ASSISTANT 消息按序持久化到 SQLite `chat_session`、`chat_message` 表。
- `/api/ai_ops` 和 `/api/ai-ops/evaluations` 已存在，但不接收对话 `sessionId`，也不将请求或结果写进聊天会话。
- 前端已有 AIOps 侧栏入口和对话模式选择；后端已有 evaluations API，但页面尚无测评构造入口。历史对话列表仍由 `localStorage` 管理，AIOps 入口当前会新建会话。
- 普通消息表目前只有 `role` 和 `content`，没有 CHAT、AIOPS_REQUEST、AIOPS_RESULT 等消息类型。

主要差距是：CHAT 与 AIOps 的入口和会话持久化尚未统一；测评没有独立的报告持久化/查询能力；模型工厂使用同一配置但普通对话和 AIOps 采用不同的生成参数，需统一模型身份并明确是否保留任务级参数差异。

## 3. 目标与非目标

### 3.1 目标

1. 普通对话、LIVE AIOps、evaluations 使用同一个 provider、模型名称、API 地址和凭据配置，同时有独立的 Agent 提示词与采样参数。
2. 普通对话与 AIOps 都以 ReAct 方式执行：模型根据观察选择工具、读取结果，再继续推理并生成最终答复。
3. CHAT 与 LIVE AIOps 关联同一个 `sessionId` 并在会话时间线上保存请求和输出；Evaluations 使用独立运行 ID 和结果存储，不写入聊天会话。
4. 页面提供普通对话、AIOps、evaluations 三个明确入口；evaluations 提供请求体构造界面。
5. 线上排查结果显示在聊天记录中并进入后续聊天上下文；测评报告不显示在聊天记录中，后续通过独立报告接口获取。首版不提供单独的运行状态查询/轮询接口。
6. evaluations 使用 REPLAY 数据源，不能访问线上工具；LIVE AIOps 使用线上工具，不能读取测评真值或基准文件。

### 3.2 非目标

- 本轮不改变 Cloud-OpsBench 的评分规则或诊断结果结构；详细的生产/测评诊断内核见现有文档《AIOps 智能体测评与生产运行一体化设计》。本文对该文档中的“生产与测评共用提示词”原则作进一步细化：诊断内核和工具契约保持一致，三类 Agent 的提示词与采样参数分别配置；评测线上能力时固定使用指定 LIVE profile 快照。
- 不在页面增加模型供应商或模型名称切换能力；模型由部署配置统一决定。
- 不引入自动执行生产变更的处置工具。线上能力仍以只读排查与建议为主。

## 4. 用户体验与入口

主页面维持一个会话工作区，并在导航区提供三种操作：

| 入口 | 用户操作 | 后端运行模式 | 会话记录 |
|---|---|---|---|
| 对话 | 输入自然语言并发送 | CHAT | USER 请求 + ASSISTANT 回复 |
| AIOps | 可输入故障描述/补充上下文，也可留空；点击开始排查 | LIVE | AIOPS_REQUEST + AIOPS_RESULT |
| Evaluations | 打开独立测评面板，填写 dataset、caseId、maxSteps 等并运行 | REPLAY | 仅测评结果存储，不写入 chat_message |

CHAT 与 LIVE AIOps 关联当前聊天 session。AIOps 输入框可空；无论是否填写，后端都必须先读取当前活跃告警，再将告警信息与用户补充描述一起交给模型判断。没有告警时也应把“当前未发现活跃告警”作为观察结果传给模型，不得跳过告警读取。AIOps 结果作为会话中的助手消息保存，并进入下一轮普通对话上下文。AIOps 运行期间，前端禁用聊天输入和发送操作；后端也按 session 阻止新聊天轮次，直至该次 AIOps 执行结束。

Evaluations 面板采用表单构造 JSON 请求体的方式：常用字段以表单呈现，提供请求预览；运行输出留在测评工作区，不新增聊天消息，也不加入 CHAT 上下文。结果表和独立报告查询接口尚未实现前，可在本次执行界面显示即时响应；持久化与之后查询报告属于后续阶段。允许从表单编辑请求，不允许前端提交任意本地文件路径。

另提供 Agent 配置页面，按「聊天」「线上 AIOps」「测评」分成三个配置区域。打开某个 profile 时读取其当前生效配置；每个区域可编辑对应提示词和采样参数，支持预览、保存未生效配置并单独应用。模型 provider/model/baseUrl/API key 仍由统一部署配置管理，不在这三个区域重复配置。

LIVE AIOps 和 REPLAY evaluation 使用独立的运行状态。AIOps 运行期间锁定所在 session 的聊天输入；SSE 内容显示为该 session 的助手结果。失败时在 AIOps 会话记录中保存请求和失败结果。Evaluation 的即时运行反馈留在测评区域，之后通过测评结果表持久化，不污染聊天记录。

## 5. 目标架构

```text
Web 工作台
  ├─ CHAT ──┐
  ├─ LIVE AIOps ─ sessionId ─→ SessionTaskController / ChatSessionService
  └─ Evaluations ─────────────→ EvaluationController / evaluation_result (later)
                                  │                    │
                                  └── ReAct Agents ─────┘
                                             ↓
                   Unified Model Connection + 3 Agent Profiles
                         ├─ CHAT tools
                         ├─ LIVE tools
                         └─ REPLAY tools
CHAT + LIVE AIOps messages → chat_session / chat_message
Evaluation request/result → evaluation workspace / evaluation_result only
```

模型配置由一个模型工厂解析一次，向各任务提供相同的模型身份（provider、model、baseUrl 和凭据来源）。Agent profile 分为 `CHAT`、`AIOPS_LIVE`、`EVALUATION`，分别保存提示词模板、temperature、maxTokens、topP、profileVersion 和更新时间；工具集合与运行模式由服务端固定，不允许通过配置页面赋予越权工具。AIOps 保留领域专用的计划与执行职责，但 Planner、Executor 与普通对话均使用同一大模型连接配置，并通过 ReAct 工具循环工作。AIOps profile 可包含 Planner/Executor 等多个提示词字段，便于针对现有多 Agent 编排分别配置。

评价可比性通过配置快照保证：每次 evaluation run 固化实际使用的 profile 名称、版本及提示词/参数快照。推荐提供“使用线上 AIOps 当前配置”选项作为评估线上能力的默认方式；如果选用独立 `EVALUATION` profile，则结果必须明确标注为该测评配置的结果，不能直接声称代表当前 LIVE 配置。三个 profile 仍可各自定制和版本化。

### 5.1 Agent 配置页面与管理 API

配置页对每个 Agent 展示独立字段：system prompt（AIOps 可拆分 Planner/Executor 字段）、temperature、maxTokens、topP、当前生效版本、最新已保存未应用版本及最近更新时间。配置操作分两步：点击“保存”新增配置版本，不影响正在运行的 Agent；点击“应用”才将最新已保存版本设为生效配置。支持恢复默认配置和应用前校验必填提示词、数值范围和最大长度。模型连接参数仅展示 provider/model 标识，不显示或编辑 API key。

建议接口：

```http
GET /api/agent-configs
GET /api/agent-configs/{profile}
PUT /api/agent-configs/{profile}/save
POST /api/agent-configs/{profile}/apply
GET /api/agent-configs/{profile}/versions
```

配置写入专用配置表或受控配置存储，保留版本历史与更新时间。`GET /{profile}` 返回当前生效配置，并在存在未应用版本时同时返回最新版本。`PUT /save` 新增配置版本但不改变运行行为；`POST /apply` 将指定的最新未应用版本切为生效版本。生效中的配置按请求快照读取，单次运行中途不因配置应用而变化。配置页面和写 API 应要求管理员权限；普通用户只能读取已生效配置的非敏感摘要。

建议把模型实例/配置解析从 `ChatService` 的聊天专属服务中抽出，提供共享工厂，例如 `ChatModelFactory`。`ChatService` 和 `AiOpsRunService` 均调用该工厂，消除两个入口的配置解析漂移。启动日志记录统一的 provider/model，不记录密钥。

## 6. API 设计

可以保留已有 endpoint 兼容调用方，并增加面向统一会话的端点。推荐由统一任务 API 承接新 UI，旧接口内部委托到同一 service。

### 6.1 普通对话

保留 `/api/chat` 与 `/api/chat_stream`。请求已有 `Id` 作为 sessionId；服务端保证会话存在，使用 Chat ReactAgent 执行，并将请求、答复以一个原子 turn 写入会话。若该 session 正在执行 AIOps，返回 HTTP `409 SESSION_TASK_IN_PROGRESS`，前端也禁用输入和发送，避免并行开启新对话轮次。响应包含最终 `sessionId`，避免客户端生成 ID 与服务端 ID 不一致。

### 6.2 LIVE AIOps

新增或扩展流式接口：

```http
POST /api/sessions/{sessionId}/aiops-runs
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "incidentPrompt": "payment-service 出现服务不可用告警，请排查根因",
  "maxSteps": 20
}
```

后端固定 `mode=LIVE`，每次执行生成唯一 `runId`，按序持久化 AIOPS_REQUEST 与 AIOPS_RESULT，并在这两条消息上写入同一 `run_id`。AIOps 请求描述即用户在界面提交的内容（当前 DTO 可映射到 `incidentPrompt`/`userRequest`）。运行结束时，将最终 Markdown 报告保存在助手消息 `content` 中，作为会话展示和后续上下文的正文；完整结构化结果和工具轨迹不塞入该消息。

### 6.3 Evaluations

```http
POST /api/evaluations/runs
Content-Type: application/json
Accept: text/event-stream
```

```json
{
  "dataset": "cloud-ops-bench",
  "caseId": "trainticket/runtime/61",
  "maxSteps": 20
}
```

接口固定 `mode=REPLAY`。首版每次只接受一个 `caseId`，不接收 `sessionId`。请求可指定评估 `AIOPS_LIVE` 的指定版本快照，或使用独立 `EVALUATION` profile；默认选项为评估当前生效的 `AIOPS_LIVE` profile。请求构造器仅开放后端支持的字段，并将该 case 的状态、报告、诊断结构和分数存入独立测评结果存储。该存储暂缓实现；持久化完成后，另提供报告查询接口。当前运行可直接返回即时报告供测评界面展示，但不得写入 `chat_message` 或作为聊天上下文。

### 6.4 会话查询

增加会话消息列表接口或扩展现有会话接口，使重载页面能从数据库恢复历史记录：

```http
GET /api/chat/session/{sessionId}/messages?beforeSequence=...
```

响应按 `sequenceNo` 升序返回 `messageId`、`role`、`messageType`、`content`、`status`、`runId`、`createdAt`。前端 `localStorage` 只用于轻量 UI 缓存，服务端 SQLite 成为持久会话历史的事实来源。

## 7. 会话数据模型

当前 `chat_message` 有 role/content/sequence。为区分任务类型和运行状态，建议新增迁移脚本（例如 `V2_chat_task_messages.sql`），不修改已发布的 `V1_init.sql`：

| 字段 | 用途 | 示例 |
|---|---|---|
| `message_type` | 消息业务类型 | `CHAT`、`AIOPS_REQUEST`、`AIOPS_RESULT` |
| `status` | 任务/结果状态 | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED` |
| `run_id` | 关联同一次 AIOps 的请求消息、结果消息和 SSE 事件 | UUID |

Agent 配置需有独立版本化存储，版本记录至少包含 `profile`、`version`、`prompt_config_json`、`sampling_config_json`、`created_at`、`applied_at`。当前生效版本由 `agent_profile.active_version` 指向；最新未应用版本从版本表按 `applied_at` 和版本号查询，不单独保存 draft 指针，因此版本表不需要 `status` 或 `created_by` 字段。evaluation 结果及 LIVE AIOps 运行都记录 profile/version；evaluation 同时固化实际提示词和参数快照或其不可变引用，便于复现。API key 等密钥不得写入 Agent profile 表。

`role` 继续使用 `USER`/`ASSISTANT`，`message_type` 只用于聊天消息和 AIOps 消息，例如 `CHAT`、`AIOPS_REQUEST`、`AIOPS_RESULT`。AIOps 请求与结果写入普通会话消息表，不另建 AIOps 请求/结果表；同一次 AIOps 的两条消息共享一个 `run_id`。Evaluations 请求和结果均不写入 `chat_message`，不参与普通对话上下文；其结构化结果和报告单独存入 evaluation 结果表。高体积 AIOps 工具轨迹不塞入聊天消息正文，可存到专门 run/trace 表。

AIOps 运行可先写入请求消息，再写 RUNNING 的结果占位消息；运行完成后更新占位记录，确保页面刷新后仍能看到已持久化状态。AIOps 请求和结果必须在同一 session 下按时间顺序排列。保存结果与更新 session `last_message_at` 应采用事务；同一 session 同时只允许一个 AIOps/CHAT 轮次，执行中的 AIOps 通过 session 级锁拒绝新聊天请求。首版不恢复或轮询仍在运行中的任务。Evaluations 不写入 `chat_session` 或 `chat_message`。

`clear` 会话行为需要覆盖新消息类型：清除普通消息时也要清除关联消息/摘要；审计轨迹是否保留由保留策略决定。数据库升级脚本依照项目现有约定，由发布人员手动执行并记录。

## 8. Agent 与上下文策略

### 8.1 CHAT

- 使用现有 Chat ReactAgent 和普通问答工具集。
- 读取 `CHAT` profile 中当前生效的提示词和采样参数。
- 按顺序读取该 session 的 `CHAT`、`AIOPS_REQUEST`、`AIOPS_RESULT` 消息作为后续上下文，并写回用户问题及助手答复。
- 同一 session 若有 AIOps 运行处于进行中状态，拒绝开始新的 CHAT 轮次。
- 工具输出继续按现有审计能力记录。

### 8.2 LIVE AIOps

- 使用统一 ChatModel 工厂与 AIOps ReAct 编排。
- 读取 `AIOPS_LIVE` profile；运行记录固定 profile 版本及采样参数快照。
- 只加载 LIVE 工具。每次运行都先读取当前活跃告警；`incidentPrompt` 可选，若提供则作为补充上下文与告警一并交给 Agent。请求仅接受 `incidentPrompt` 和 `maxSteps`；告警查询范围由后端逻辑决定。
- 将请求和最终报告记录在当前 session；诊断状态、runId、关键证据摘要可查询。
- 线上排查结果可以作为后续普通对话的可见上下文；上下文构造时只传递报告摘要，不把隐藏工具原始数据无限追加到 prompt。

### 8.3 Evaluations

- 使用与 LIVE 同一诊断内核、模型连接配置和固定 REPLAY 工具提供者；Agent 提示词/采样参数可选 LIVE profile 快照或独立的 `EVALUATION` profile。
- Agent 只见案例问题和工具回放结果；不得见基准真值、过程标签或评分文件。
- 首版每次只运行一个 case。评分器完成打分后，结果目标为单独的 `evaluation_result` 表；不写入任何聊天 session 或 `chat_message`。结果表实现暂缓，持久化后通过独立报告接口查询。
- Evaluation 请求、工具观察、诊断结论和评分均不加入普通聊天的模型上下文，也不显示在聊天记录中；仅在测评工作区展示或由测评报告接口返回。
- 记录本次评测所用 profile 名称、版本和配置快照；若使用独立测评提示词，结果需明确标示，避免与线上 Agent 质量混淆。

## 9. 前端状态与交互

1. CHAT 和 LIVE AIOps 使用当前 `sessionId`；Evaluations 独立运行，不携带聊天 `sessionId`。
2. 页面启动/切换历史会话时从服务端加载 messages；本地缓存可优化首屏但必须与服务端序列号合并。
3. AIOps 入口打开输入面板，故障描述为可选项。提交后无条件读取当前活跃告警，并将告警及用户补充描述共同放入 Agent 上下文。请求只传 `incidentPrompt` 与 `maxSteps`。
4. Evaluations 入口打开独立参数面板，展示 dataset、caseId、maxSteps、请求 JSON 预览；运行结果在测评区域展示，不插入聊天时间线。
5. 流式事件需区分 `run_started`、`message`、`run_completed`、`run_failed`。AIOps 事件携带 sessionId/runId/messageId；Evaluation 事件携带 evaluationRunId/runId，不携带 session/message 标识。
6. 浏览器刷新后通过会话消息接口重新加载已持久化的请求和结果；首版不恢复或轮询仍在运行中的任务。
7. Agent 配置页面按 Chat、AIOps、Evaluations 分区显示各自提示词及采样参数；保存不影响线上运行，应用后新运行读取新版本，已运行任务保留旧版本快照。

## 10. 兼容、错误与安全约束

- 旧 `/api/ai_ops` 和 `/api/ai-ops/evaluations` 保持兼容；旧 Evaluation 入口不写聊天记录。未提供 sessionId 的旧 AIOps 调用建议创建 session 并保存 AIOps 会话消息。
- AIOps 失败时在聊天会话中保存请求和 FAILED 结果消息；Evaluation 失败写入（未来实现的）测评结果记录或返回安全错误。内部异常、密钥、敏感日志不可直接返回浏览器。
- REPLAY caseId 必须白名单校验并防止路径穿越；前端不能指定数据集目录。
- REPLAY 不访问线上 Prometheus、日志或 Kubernetes；LIVE 不读取基准真值和本地 benchmark 文件。
- LIVE 默认只读；任何执行类运维工具必须独立权限与显式审批，不纳入本设计默认流程。
- AIOps/evaluation endpoint 应继承现有认证/网络访问策略；测评接口尤其应限于授权用户或内网调用方。
- Agent 配置保存与应用需要管理员权限；提示词需长度和格式校验，模型工具 allowlist 由服务端代码控制，不能通过可编辑提示词或配置扩大。
- SSE 断开不应取消运行任务。使用 `runId` 做幂等，避免客户端重试造成重复排查或评分；首版不提供 run 状态查询接口。

## 11. 分阶段实施

### 阶段一：会话模型与统一模型工厂

- 抽取共享 ChatModel 工厂，让 ChatService 与 AiOpsRunService 使用同一模型标识和连接配置。
- 为 `chat_message` 增加 messageType/status/runId，并提供迁移脚本。
- 增加从数据库读取完整会话消息的 API。
- 定义三类 Agent profile 数据结构和默认配置；保留版本与运行快照字段。

**验收**：同一配置下 CHAT、LIVE、REPLAY 日志显示相同 provider/model；迁移后旧会话可正常读取。

### 阶段二：LIVE AIOps 会话化

- endpoint 接收 sessionId 和可选 incident 描述；后端无条件读取当前活跃告警，并与可选描述合并后作为 Agent 输入。
- 请求先写入 session，运行中更新状态，完成后写回报告和结构化摘要。
- 前端 AIOps 面板在当前会话运行，刷新可恢复结果。

**验收**：普通聊天后能在同一时间线运行线上排查；重载后仍可读取请求、状态和结果。

### 阶段三：Evaluations 工作台

- 构造请求的表单和 JSON 预览。
- 首版仅支持单 case；测评请求/结果不写入聊天记录，结构化结果和分数写入独立表，结果表及报告查询接口列为后续实现。
- 支持选择以当前 AIOps profile 快照测评（默认），或用独立 EVALUATION profile；运行结果保存所用配置版本。
- 明确分数、runId 和报告的展示及导出。

**验收**：一个测评 case 可从 UI 发起并在本次测评页面得到报告/分数，且运行日志确认仅使用 REPLAY 数据源；结果表和持久报告读取列为后续实现。

### 阶段四：旧入口兼容与完善

- 旧接口委托新 service；确定无 sessionId 请求的兼容策略。
- 增加幂等、防并发冲突、历史分页和消息保留策略；运行中任务恢复及状态查询接口暂缓。

### 阶段五：Agent 配置管理页面

- 建立 Chat、AIOps、Evaluations 三类 profile 的提示词与采样参数配置。
- 实现管理员配置页、保存/应用、默认值恢复、版本历史和变更审计。
- 新运行读取已应用版本并固化配置快照；配置更新不改变已运行任务。

**验收**：三个 Agent 可单独调整提示词和采样参数；保存不改变当前生效配置，应用某一个 profile 不修改其余 profile；运行记录能指出具体配置版本；evaluation 默认可复现并评估指定的线上 AIOps profile。

## 12. 已确认的产品决策与实现默认值

- AIOps 输入可选；无论是否输入，都先读取当前活跃告警。若用户填写了描述，将其与当前告警共同放入模型上下文。
- 普通聊天、LIVE AIOps 和 REPLAY evaluation 使用相同的 provider、模型名称、API 地址及凭据配置。
- “相同大模型”指使用同一个模型服务与模型版本。Temperature、maxTokens、topP 是控制回答随机程度、回答长度上限和采样偏好的生成参数，不是不同的大模型。设计默认允许各任务保留合适的生成参数；如需连这些参数也完全一致，再统一配置即可。
- LIVE AIOps 在当前 session 运行，用户请求和 AIOps 结果写入会话消息表；AIOps 结果可作为后续聊天上下文。
- Evaluations 首版只支持单 case。请求/结果均不进入聊天记录或后续普通聊天上下文；结构化测评结果和报告规划存入独立结果表，结果表及报告查询接口实现暂缓。
- `aiops-request` 表示会话中的 AIOPS_REQUEST 消息，不创建独立请求表。
- Chat、LIVE AIOps、Evaluations Agent 配置相互隔离，分别可定制提示词与采样参数；大模型连接配置共享。
- Evaluations 默认评估当前生效的 LIVE AIOps profile 快照，同时允许使用独立 EVALUATION profile 做专项实验；后者的结果应标注配置版本。

旧 `/api/ai_ops` 调用未携带 `sessionId` 时，建议自动创建 session 并保存 AIOps 请求/结果；旧 evaluation 调用继续返回运行输出，但不写入聊天会话。

## 13. 实现接口与数据库表清单

本节将前述设计收敛为实现任务清单。路径为建议路径；如需兼容已有客户端，旧接口继续保留并委托到相同的 Service。

### 13.1 接口清单

| 类型 | 方法与路径 | 状态 | 用途 |
|---|---|---|---|
| 对话 | `POST /api/chat` | 改造 | 使用 `CHAT` Agent profile，按既有逻辑读写 session；响应增加 `sessionId` 和消息标识。 |
| 对话流式 | `POST /api/chat_stream` | 改造 | 使用 `CHAT` profile；SSE 完成事件返回持久化后的 session/message 标识。 |
| LIVE AIOps | `POST /api/sessions/{sessionId}/aiops-runs` | 新增 | 接收可选故障描述，每次均读取当前活跃告警；两者一起进入 AIOps Agent 上下文。请求与结果写入当前 session。 |
| Evaluations | `POST /api/evaluations/runs` | 新增 | 单 case REPLAY，不绑定聊天 session。默认使用当前生效的 `AIOPS_LIVE` profile 快照；允许显式选择 `EVALUATION` profile 或指定版本。即时响应仅供测评页面展示。 |
| 测评报告 | `GET /api/evaluations/runs/{evaluationRunId}/report` | 后续 | 从 `evaluation_result` 获取已持久化测评报告；结果表和接口暂缓实现。 |
| 会话消息 | `GET /api/chat/session/{sessionId}/messages` | 新增 | 按 sequence 返回已持久化 CHAT/AIOps 消息，供刷新页面恢复聊天记录。 |
| 任务状态 | `GET /api/sessions/{sessionId}/runs/{runId}` | 暂缓 | 首版不提供单独状态查询/轮询；SSE 推送本次运行结果，会话消息接口读取已持久化记录。 |
| Agent 配置概览 | `GET /api/agent-configs` | 新增 | 返回 CHAT、AIOPS_LIVE、EVALUATION 当前生效版本与非敏感模型标识。 |
| Agent 配置读取 | `GET /api/agent-configs/{profile}` | 新增 | 返回 profile 当前生效版本及（若存在）待应用版本的提示词/采样参数；需管理员权限。 |
| Agent 配置保存 | `PUT /api/agent-configs/{profile}/save` | 新增 | 保存提示词和采样参数为待应用版本，不影响正在运行的 Agent。 |
| Agent 配置应用 | `POST /api/agent-configs/{profile}/apply` | 新增 | 将指定待应用版本设为生效版本；新运行使用新版本，已有运行保持其启动时快照。 |
| Agent 配置版本 | `GET /api/agent-configs/{profile}/versions` | 新增 | 查看 profile 历史版本及创建、应用时间。 |

#### 普通对话请求与响应

保留现有 `Id`、`Question` 字段兼容；服务端将 `Id` 作为 sessionId。建议响应至少增加：

```json
{
  "sessionId": "session-123",
  "userMessageId": "message-user-1",
  "assistantMessageId": "message-assistant-1",
  "answer": "..."
}
```

#### LIVE AIOps 请求

```json
{
  "incidentPrompt": "payment-service 最近出现超时，请检查原因",
  "maxSteps": 20
}
```

`incidentPrompt` 可选，`maxSteps` 可选且默认值为 20。后端必须调用当前告警工具；告警结果与用户输入合并为 Agent 输入。即使用户未输入描述，也创建一条 `AIOPS_REQUEST` 会话消息，例如“基于当前活跃告警进行排查”，以便会话时间线清楚展示任务起因。

#### Evaluations 请求

```json
{
  "dataset": "cloud-ops-bench",
  "caseId": "trainticket/runtime/61",
  "maxSteps": 20,
  "agentProfile": "AIOPS_LIVE",
  "profileVersion": 3
}
```

`agentProfile` 缺省为 `AIOPS_LIVE`，缺省 `profileVersion` 表示在启动时解析当前生效版本。也可指定 `EVALUATION` 和对应版本。接口固定 REPLAY 模式，拒绝批量 `caseIds`、本地路径及线上工具选项。

#### SSE 事件约定

LIVE AIOps/evaluation 的流式端点使用统一事件结构，至少包含：

```json
{"type":"run_started","taskType":"AIOPS","sessionId":"...","runId":"...","requestMessageId":"...","resultMessageId":"..."}
{"type":"message","runId":"...","delta":"正在分析告警..."}
{"type":"run_completed","taskType":"AIOPS","runId":"...","resultMessageId":"...","sequenceNo":4}
```

Evaluation 使用相同事件类型，并设置 `taskType=EVALUATION`、携带 `evaluationRunId`，不发送 sessionId/messageId。失败事件为 `run_failed`，包含安全错误码、用户可见摘要；AIOps 事件还包含结果消息标识。AIOps 运行先创建请求消息和 `RUNNING` 助手占位消息；结束时更新占位消息及状态。SSE 断开后可从会话消息读取 AIOps 已保存内容，但首版不通过 `runId` 轮询运行进度或恢复运行中状态。Evaluation 结果由测评页面即时响应展示。

### 13.2 数据库表清单

数据库为 SQLite；新增结构通过新的手动迁移脚本（建议 `src/main/resources/db/V2_unified_agent_workspace.sql`）实现，不修改已应用的 V1 初始化脚本。

#### 现有 `chat_session`（保留）

继续作为会话主表，使用现有 `session_id`、`title`、摘要、创建/最后活跃时间字段。普通聊天和 AIOps 的可见消息关联 `session_id`。Evaluations 不创建会话消息。

#### 现有 `chat_message`（增加字段）

保留现有 `message_id`、`session_id`、`sequence_no`、`role`、`content`、`trace_id` 和时间字段；新增：

| 字段 | 类型建议 | 约束/用途 |
|---|---|---|
| `message_type` | `TEXT` | NOT NULL，缺省 `CHAT`；值为 `CHAT`、`AIOPS_REQUEST`、`AIOPS_RESULT`。 |
| `status` | `TEXT` | 可空；任务结果消息使用 `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。 |
| `run_id` | `TEXT` | 可空；按 UUID 字符串保存。SQLite 没有原生 UUID 类型，且 Java API 以字符串传递。AIOps 请求消息和结果消息共享同一个 ID，用于关联同一次执行及 SSE 事件。首版不依赖该字段提供状态查询；`GET /api/sessions/{sessionId}/runs/{runId}` 暂缓。 |

索引建议：`(session_id, sequence_no)` 唯一索引沿用；增加 `run_id` 索引和 `(session_id, message_type, sequence_no)` 查询索引。AIOps 请求与报告只使用此表，不增加 AIOps request/result 专表。Evaluation 不在此表留下请求或结果记录。

#### 新表 `agent_profile`

每个逻辑 Agent 一条当前配置指针记录：

| 字段 | 类型建议 | 说明 |
|---|---|---|
| `profile` | `TEXT PRIMARY KEY` | `CHAT`、`AIOPS_LIVE`、`EVALUATION`。 |
| `active_version` | `INTEGER` | 当前生效的配置版本号。 |
| `updated_at` | `TEXT` | 最近更新时间。 |

#### 新表 `agent_profile_version`

保存待应用配置和历史版本：

| 字段 | 类型建议 | 说明 |
|---|---|---|
| `id` | `TEXT PRIMARY KEY` | 配置版本记录 ID。 |
| `profile` | `TEXT NOT NULL` | 所属 profile。 |
| `version_no` | `INTEGER NOT NULL` | profile 内递增版本；与 profile 组合唯一。 |
| `prompt_config_json` | `TEXT NOT NULL` | 提示词配置；AIOps 可包含 Planner/Executor 多个字段。 |
| `sampling_config_json` | `TEXT NOT NULL` | temperature、maxTokens、topP 等参数。 |
| `created_at` | `TEXT NOT NULL` | 创建时间。 |
| `applied_at` | `TEXT` | 该版本最近一次被应用的时间；未应用版本为 NULL。 |

唯一约束：`UNIQUE(profile, version_no)`。打开 profile 时通过 `active_version` 读取生效配置；最新的 `applied_at IS NULL` 版本（且版本号大于当前 active version）作为待应用版本返回。每次点击保存都新增一个递增版本，不修改旧版本。点击应用时校验目标版本是最新未应用版本，然后将 `active_version` 原子更新为该版本并写入 `applied_at`。要回退到历史提示词时，应将旧配置复制为一个新版本再应用，保持版本号单调递增。API key、baseUrl 密钥等凭据不存入这两张配置表，仍使用部署配置。

#### 后续新表 `evaluation_result`（本期先设计，不要求立即实现）

存储结构化测评产物及可读报告。该表不依赖聊天 session 或 `chat_message`：

| 字段 | 类型建议 | 说明 |
|---|---|---|
| `evaluation_run_id` | `TEXT PRIMARY KEY` | 测评运行 ID；独立于 `chat_message.run_id`，因为 Evaluation 不写入聊天消息表。 |
| `dataset` | `TEXT NOT NULL` | 数据集标识。 |
| `case_id` | `TEXT NOT NULL` | 单个 case 标识。 |
| `status` | `TEXT NOT NULL` | `PENDING`、`RUNNING`、`SUCCEEDED`、`FAILED`。 |
| `profile` | `TEXT NOT NULL` | 实际使用的 `AIOPS_LIVE` 或 `EVALUATION`。 |
| `profile_version` | `INTEGER NOT NULL` | 启动时固化的配置版本。 |
| `config_snapshot_json` | `TEXT` | 用于复现的提示词/参数快照或不可变引用。 |
| `model_provider` / `model_name` | `TEXT` | 实际使用的模型标识，不含凭据。 |
| `diagnosis_json` | `TEXT` | 结构化诊断结果。 |
| `scores_json` | `TEXT` | 测评评分明细。 |
| `report_markdown` | `TEXT` | 可读测评报告，由后续测评报告接口返回。 |
| `error_code` / `error_message` | `TEXT` | 失败状态及安全摘要。 |
| `started_at` / `finished_at` | `TEXT` | 运行起止时间。 |
| `created_at` | `TEXT NOT NULL` | 记录创建时间。 |

索引建议：`(dataset, case_id, created_at)`、`(profile, profile_version)`。工具调用细节继续使用现有工具审计表或既有 trace 设计；不将大型 trace JSON 写入 `chat_message`。

### 13.3 实现顺序与边界

1. **先实现**：共享模型连接工厂、三种 profile 的加载能力、CHAT/AIOPS_LIVE/EVALUATION 默认配置、配置版本读取；增加 `chat_message` 字段和会话消息查询。
2. **随后实现**：LIVE AIOps 会话 API 和前端入口；落实每次运行读取当前告警、可选提示合并、SSE 和结果消息持久化。
3. **随后实现**：单 case evaluation API/独立页面，响应结果不进入聊天记录或上下文。`evaluation_result` 表和 `GET /api/evaluations/runs/{evaluationRunId}/report` 报告接口可延期实现。
4. **配置页面**：管理员可编辑三类 Agent 的提示词和采样参数，保存和应用分离，变更版本可审计；模型连接配置不在页面编辑。
5. **兼容处理**：旧 `/api/ai_ops`、`/api/ai-ops/evaluations` 保留并委托新服务。无 `sessionId` 的旧 AIOps 调用建议自动创建 session 并保存；evaluation 调用不创建 session、不写聊天记录。单独的 run 状态查询/轮询接口列为后续增强。
