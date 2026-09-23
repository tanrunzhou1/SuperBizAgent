# AIOps 智能体测评与生产运行一体化设计

## 1. 目标

本设计将 AIOps 能力拆分为两个对外接口：一个处理真实运维事件，另一个运行 Cloud-OpsBench 测评案例。两者复用同一套智能体编排、提示词、工具契约、结构化诊断结果和 Markdown 报告渲染逻辑。

这样，测评不再只是离线打分，而是生产能力的持续回归验证；测评案例生成的报告也与真实运维人员看到的报告保持同一格式与质量标准。

## 2. 设计原则

1. **诊断内核一致**：生产和测评必须调用相同的 Agent 编排、模型配置、提示词和输出约束。
2. **仅替换数据源**：生产读取真实 Prometheus、日志平台和 Kubernetes；测评从 Cloud-OpsBench 冻结快照回放。
3. **诊断与评分分离**：Agent 只能访问案例问题和工具返回数据，不能访问根因真值或过程标签。
4. **结构化结果为单一事实来源**：Agent 先生成可验证的结构化诊断，再由程序确定性渲染 Markdown 报告。
5. **测评可重复**：同一案例、模型、提示词和工具返回应可重复执行并比较结果。
6. **生产安全优先**：生产模式的建议默认只读、只建议，不自动执行高风险处置操作。

## 3. 总体架构

```text
POST /api/ai-ops                         POST /api/ai-ops/evaluations
        │                                           │
        └───────────────┬───────────────────────────┘
                        ↓
                  AiOpsRunService
                        ↓
               AiOpsDiagnosisEngine
          同一 Agent / Prompt / 输出契约
                        ↓
          ┌─────────────┴─────────────┐
          ↓                           ↓
 LiveAiOpsToolProvider       ReplayAiOpsToolProvider
 Prometheus / 日志 / K8s      Cloud-OpsBench tool_cache
          └─────────────┬─────────────┘
                        ↓
                  DiagnosisResult
                        ↓
              MarkdownReportRenderer
                        ↓
              运维人员可读 Markdown 报告
                        ↓
      仅在测评模式：EvaluationService 读取真值并评分
```

## 4. 接口设计

### 4.1 真实运维接口

保留现有 `POST /api/ai_ops` 作为兼容入口；后续可演进为接收 JSON 请求体。

```json
{
  "incidentPrompt": "payment-service 出现服务不可用告警，请排查根因",
  "serviceScope": ["payment-service"],
  "timeRange": {
    "start": "2026-09-21T10:00:00+08:00",
    "end": "2026-09-21T10:30:00+08:00"
  },
  "maxSteps": 12
}
```

内部转换为 `mode=LIVE` 的运行上下文。响应可以继续采用 SSE 推送报告内容，并应同步保存 `runId`、结构化诊断结果和工具调用轨迹。

### 4.2 测评接口

新增仅供内部管理员和 CI 调用的接口：

```text
POST /api/ai-ops/evaluations
```

请求示例：

```json
{
  "dataset": "cloud-ops-bench",
  "caseIds": ["trainticket/runtime/61"],
  "modelProfile": "baseline",
  "maxSteps": 12
}
```

该接口创建 `mode=REPLAY` 的运行上下文。`caseId` 必须采用白名单格式，例如 `系统/故障类别/数字案例号`；不得允许调用方传入文件路径。

批量测评应使用异步任务或 CI Runner，避免长时间 HTTP 请求。接口可先返回 `evaluationRunId`，再通过查询接口获取每个案例的结果。

## 5. 核心领域模型

### 5.1 运行上下文

```java
public record AiOpsRunContext(
        String runId,
        AiOpsRunMode mode,       // LIVE、REPLAY
        String incidentPrompt,
        String caseId,
        int maxSteps,
        List<String> serviceScope,
        TimeRange timeRange) {}
```

`AiOpsRunContext` 是编排层唯一依赖的输入。生产与测评差异不应散落在 Planner、Executor 或 Controller 中。

### 5.2 结构化诊断结果

```json
{
  "runId": "run-20260921-001",
  "mode": "REPLAY",
  "status": "CONFIRMED",
  "faultObject": "app/ts-delivery-service",
  "rootCause": "mysql_invalid_port",
  "confidence": 0.91,
  "impact": "配送服务不可用",
  "keyEvidence": [
    {
      "toolCallId": "tool-02",
      "tool": "GetErrorLogs",
      "finding": "数据库连接端口异常"
    }
  ],
  "recommendedActions": [
    {
      "priority": "P0",
      "action": "修正数据库端口配置后滚动发布",
      "risk": "需要配置变更"
    }
  ]
}
```

`faultObject` 和 `rootCause` 用于 Cloud-OpsBench 的结果评分。生产场景允许这两个字段使用企业内部标准术语，但需要维护术语映射层，避免不同命名导致统计失真。

### 5.3 工具调用轨迹

每次调用必须记录：

```text
runId、caseId、step、toolCallId、工具名、参数、返回摘要、耗时、错误、时间戳
```

现有工具审计可继续承担持久化审计职责；同时需要面向单次运行的有序 `AiOpsRunTrace`，用于导出 Cloud-OpsBench 所需的轨迹格式和计算过程指标。

## 6. 工具提供者抽象

定义统一接口：

```java
public interface AiOpsToolProvider {
    ToolCallback[] getTools(AiOpsRunContext context);
}
```

### 6.1 生产实现：`LiveAiOpsToolProvider`

返回真实工具，包括但不限于：

- Prometheus 告警与时间窗口指标查询；
- 日志平台查询；
- Kubernetes 资源、配置和事件查询；
- 服务依赖与连通性查询；
- 内部 Runbook / 知识库检索。

### 6.2 测评实现：`ReplayAiOpsToolProvider`

根据 `caseId` 加载 Cloud-OpsBench 对应案例的 `tool_cache.json`，以与生产工具一致的名称、参数和响应语义返回冻结数据。

初始应实现以下工具，以覆盖首批 runtime、service 和 performance 案例：

| 工具 | 生产数据源 | 测评数据源 |
|---|---|---|
| `GetAlerts` | Prometheus / Alertmanager | `tool_cache.json` |
| `GetRecentLogs` | 日志平台 | `tool_cache.json` |
| `GetErrorLogs` | 日志平台 | `tool_cache.json` |
| `GetResources` | Kubernetes API | `tool_cache.json` |
| `DescribeResource` | Kubernetes API | `tool_cache.json` |
| `GetAppYAML` | Kubernetes API / GitOps | `tool_cache.json` |
| `GetServiceDependencies` | 服务注册或拓扑系统 | `tool_cache.json` |
| `CheckServiceConnectivity` | 探测服务 | `tool_cache.json` |

测评模式禁止任何真实网络调用；生产模式禁止读取本地 Benchmark 文件。

## 7. Agent 输出与报告生成

Agent 的最终输出应为严格 JSON，而不是直接输出 Markdown。系统负责：

1. 校验 JSON 的必填字段和类型；
2. 将证据关联至已记录的 `toolCallId`；
3. 生成 `DiagnosisResult`；
4. 使用 `MarkdownReportRenderer` 渲染固定格式报告；
5. 对测评模式调用评分器。

报告模板至少包含：

- 事件与影响范围；
- 诊断状态和置信度；
- 关键证据及其来源；
- 根因结论；
- 建议处置步骤、风险和验证方式；
- 证据不足或工具失败时的明确说明。

当结构化输出校验失败时，运行应标记为 `INVALID_OUTPUT`；可保留模型原始文本用于排障，但不能将其当作可评分或可自动执行的诊断结果。

## 8. 测评隔离与评分

### 8.1 必须隔离的内容

Cloud-OpsBench 的以下内容只允许评分器访问：

- `metadata.json` 中的 `result`；
- `process-label/*/milestone.json`；
- 官方评分结果。

Agent 只可得到案例问题和通过回放工具获取的观测数据。这样才能避免真值泄漏。

### 8.2 评分产物

每个测评案例产生独立的 `evaluation.json`：

```json
{
  "runId": "run-20260921-001",
  "caseId": "trainticket/runtime/61",
  "scores": {
    "componentAccuracy": 1,
    "faultAccuracy": 1,
    "jointRcaAccuracy": 1,
    "milestoneCoverage": 0.83,
    "evidenceOrderConsistency": 1,
    "evidenceEfficiency": 0.71,
    "steps": 6,
    "redundantActionRate": 0.0
  }
}
```

生产 `LIVE` 模式不应产生 Benchmark 分数；它只生成诊断、报告和轨迹。后续可由人工对生产报告标记正确性，并沉淀为自有测评集。

## 9. 实施顺序

### 阶段一：统一运行契约

1. 扩展现有 `AIOpsRequest`，引入 `AiOpsRunContext`。
2. 将 `AiOpsService` 的硬编码任务改为根据上下文生成。
3. 定义 `DiagnosisResult`、`AiOpsRunTrace` 和 `MarkdownReportRenderer`。
4. 将 Planner 最终输出从纯 Markdown 改为严格 JSON。

**验收标准**：同一个 Live 调用能够输出结构化诊断、Markdown 报告和有序工具轨迹。

### 阶段二：单案例回放

1. 新增 `ReplayAiOpsToolProvider`。
2. 对接 Cloud-OpsBench 的一个案例，例如 `trainticket/runtime/2`。
3. 实现告警、日志、Kubernetes 资源和配置的回放工具。
4. 新增单案例测评接口。

**验收标准**：指定 `caseId` 后，Agent 不访问真实系统即可完成诊断并输出报告。

### 阶段三：自动评分与批量回归

1. 导出兼容 Cloud-OpsBench 的轨迹 JSON，或复刻同等评分规则。
2. 先选择 20 至 30 个案例，按故障类型划分开发集和验收集。
3. 在 CI 中固定模型、Prompt、工具版本并生成趋势报告。

**验收标准**：每次 Agent、Prompt 或工具改动后，能够自动获得结果正确性和过程质量的对比报告。

### 阶段四：生产能力对齐

1. 接入真实 Kubernetes、Prometheus 和日志平台。
2. 将生产工具与回放工具保持同一参数和响应契约。
3. 对生产报告引入人工反馈与审核机制，沉淀为企业自有案例集。

## 10. 安全与运行约束

- 测评接口必须仅对管理员、CI 或内网调用方开放。
- `caseId` 必须白名单校验，禁止目录穿越和任意文件读取。
- 回放模式不得访问生产监控、日志、Kubernetes 或外部执行工具。
- 生产模式中的自动处置必须分级；默认只提供建议，执行型工具需额外审批。
- 工具返回和报告输出应执行敏感信息脱敏。
- 模型、Prompt、工具定义、数据集 commit SHA 和评测配置必须共同记录，保证结果可追溯。

## 11. 成功标准

该设计完成后，系统应满足：

1. 同一 Agent 内核可以处理真实故障和冻结测评案例。
2. 同一份 `DiagnosisResult` 可以生成运维 Markdown 报告，也可以被评分器自动判分。
3. 测评结果能够作为每次 Agent 改动的回归门禁。
4. 生产运行积累的人工反馈可以持续扩充企业专属测评集。
