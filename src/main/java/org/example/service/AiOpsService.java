package org.example.service;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.flow.agent.SupervisorAgent;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.agent.tool.DateTimeTools;
import org.example.agent.tool.InternalDocsTools;
import org.example.agent.tool.QueryLogsTools;
import org.example.agent.tool.QueryMetricsTools;
import org.example.common.aiops.AiOpsRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * AI Ops 智能运维服务
 * 负责多 Agent 协作的告警分析流程
 */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)  // Mock 模式下才注册
    private QueryLogsTools queryLogsTools;

    /**
     * 执行 AI Ops 告警分析流程
     *
     * @param chatModel      大模型实例
     * @param toolCallbacks  工具回调数组
     * @return 分析结果状态
     * @throws GraphRunnerException 如果 Agent 执行失败
     */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks,
                AiOpsRunContext.live("请读取当前活跃告警并执行故障分析。"));
    }

    /**
     * 使用统一运行上下文执行 AI Ops 分析。旧方法保留用于兼容已有调用方。
     */
    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel,
            ToolCallback[] toolCallbacks, AiOpsRunContext runContext) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks, runContext, Map.of());
    }

    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel,
            ToolCallback[] toolCallbacks, AiOpsRunContext runContext,
            Map<String, String> prompts) throws GraphRunnerException {
        logger.info("开始执行 AI Ops 多 Agent 协作流程");

        // 构建 Planner 和 Executor Agent
        ReactAgent plannerAgent = buildPlannerAgent(chatModel, toolCallbacks, runContext, prompts);
        ReactAgent executorAgent = buildExecutorAgent(chatModel, toolCallbacks, runContext, prompts);

        // 构建 Supervisor Agent
        SupervisorAgent supervisorAgent = SupervisorAgent.builder()
                .name("ai_ops_supervisor")
                .description("负责调度 Planner 与 Executor 的多 Agent 控制器")
                .model(chatModel)
                .systemPrompt(promptOrDefault(prompts, "supervisor", buildSupervisorSystemPrompt()))
                .subAgents(List.of(plannerAgent, executorAgent))
                .compileConfig(compileConfig(runContext))
                .build();

        String taskPrompt = runContext == null
                ? AiOpsRunContext.live("请读取当前活跃告警并执行故障分析。").taskPrompt()
                : runContext.taskPrompt();

        logger.info("调用 Supervisor Agent 开始编排...");
        return supervisorAgent.invoke(taskPrompt);
    }

    /**
     * 从执行结果中提取最终报告文本
     *
     * @param state 执行状态
     * @return 报告文本（如果存在）
     */
    public Optional<String> extractFinalReport(OverAllState state) {
        logger.info("开始提取最终报告...");

        List<String> candidates = new ArrayList<>();
        collectReportCandidate(state.data().get("planner_plan"), candidates);
        collectReportCandidate(state.data().get("messages"), candidates);
        collectReportCandidate(state.data().get("executor_feedback"), candidates);

        // 优先选择真正包含结构化诊断字段的输出，避免把中间工具调用文本当成最终报告。
        for (String candidate : candidates) {
            if (candidate.contains("faultObject") && candidate.contains("rootCause")) {
                logger.info("成功提取到结构化 Planner 最终报告，长度: {}", candidate.length());
                return Optional.of(candidate);
            }
        }
        if (!candidates.isEmpty()) {
            String fallback = candidates.get(0);
            logger.warn("未找到结构化最终报告，返回最后一个 Agent 文本，长度: {}", fallback.length());
            return Optional.of(fallback);
        }
        logger.warn("未能提取到 Planner 最终报告");
        return Optional.empty();
    }

    private void collectReportCandidate(Object value, List<String> candidates) {
        if (value instanceof AssistantMessage assistantMessage) {
            if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                candidates.add(assistantMessage.getText());
            }
        }
        else if (value instanceof Message message && message.getText() != null && !message.getText().isBlank()) {
            candidates.add(message.getText());
        }
        else if (value instanceof Collection<?> collection) {
            for (Object item : collection) {
                collectReportCandidate(item, candidates);
            }
        }
        else if (value instanceof String text && !text.isBlank()) {
            candidates.add(text);
        }
    }

    /**
     * 构建 Planner Agent
     */
    private ReactAgent buildPlannerAgent(ChatModel chatModel, ToolCallback[] toolCallbacks,
            AiOpsRunContext runContext, Map<String, String> prompts) {
        return ReactAgent.builder()
                .name("planner_agent")
                .description("负责拆解告警、规划与再规划步骤")
                .model(chatModel)
                .systemPrompt(promptOrDefault(prompts, "planner", buildPlannerPrompt()))
                .methodTools(buildMethodToolsArray(runContext))
                .tools(toolCallbacks)
                .compileConfig(compileConfig(runContext))
                .outputKey("planner_plan")
                .build();
    }

    /**
     * 构建 Executor Agent
     */
    private ReactAgent buildExecutorAgent(ChatModel chatModel, ToolCallback[] toolCallbacks,
            AiOpsRunContext runContext, Map<String, String> prompts) {
        return ReactAgent.builder()
                .name("executor_agent")
                .description("负责执行 Planner 的首个步骤并及时反馈")
                .model(chatModel)
                .systemPrompt(promptOrDefault(prompts, "executor", buildExecutorPrompt()))
                .methodTools(buildMethodToolsArray(runContext))
                .tools(toolCallbacks)
                .compileConfig(compileConfig(runContext))
                .outputKey("executor_feedback")
                .build();
    }

    /**
     * 图递归次数包含 Supervisor/Planner/Executor 的路由节点，不能直接等同于工具步数。
     * 工具步数由 AiOpsRunService 的共享预算严格限制；这里给图本身留出足够的路由空间。
     */
    private CompileConfig compileConfig(AiOpsRunContext runContext) {
        int maxSteps = runContext == null ? 20 : Math.max(1, runContext.getMaxSteps());
        return CompileConfig.builder().recursionLimit(Math.max(20, maxSteps * 10)).build();
    }

    /**
     * 动态构建方法工具数组
     * 根据 cls.mock-enabled 决定是否包含 QueryLogsTools
     */
    private Object[] buildMethodToolsArray(AiOpsRunContext runContext) {
        // REPLAY 只能使用 Cloud-OpsBench 的 ToolCallback，不能把任何真实
        // Prometheus、日志或知识库工具暴露给 Agent。
        if (runContext != null && runContext.getMode() == org.example.common.aiops.AiOpsRunMode.REPLAY) {
            return new Object[0];
        }
        if (queryLogsTools != null) {
            // Mock 模式：包含 QueryLogsTools
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        } else {
            // 真实模式：不包含 QueryLogsTools（由 MCP 提供日志查询功能）
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
        }
    }

    /**
     * 构建 Planner Agent 系统提示词
     */
    private String buildPlannerPrompt() {
        return """
                你是 Planner Agent，同时承担 Replanner 角色，负责：
                1. 读取当前输入任务 {input} 以及 Executor 的最近反馈 {executor_feedback}。
                2. 分析 Prometheus 告警、日志、内部文档等信息，制定可执行的下一步步骤。
                3. 在执行阶段，输出 JSON，包含 decision (PLAN|EXECUTE|FINISH)、step 描述、预期要调用的工具、以及必要的上下文。
                4. 调用任何腾讯云日志/主题相关工具时，region 参数必须使用连字符格式（如 ap-guangzhou），若不确定请省略以使用默认值。
                5. 严格禁止编造数据，只能引用工具返回的真实内容。工具返回 success=false 时，表示代码已完成该工具的系统级重试；不得以相同参数重复调用，应切换排查方向或在最终报告说明"无法完成"的原因。success=false 的返回绝不是证据，不得写入 keyEvidence。
                6. 当工具返回 code=MAX_STEPS_EXCEEDED 时，立即停止工具调用并输出最终 JSON；不得再次调用任何工具。
                
                ## 最终报告输出要求（CRITICAL）
                
                当 decision=FINISH 时，你必须：
                1. **最终只输出结构化 JSON，Markdown 报告由服务端渲染**
                2. **JSON 必须包含 faultObject、rootCause、confidence、impact、keyEvidence、recommendedActions**
                3. **以下模板仅用于说明报告内容，最终不得直接输出 Markdown**：
                
                ```
                # 告警分析报告
                
                ---
                
                ## 📋 活跃告警清单
                
                | 告警名称 | 级别 | 目标服务 | 首次触发时间 | 最新触发时间 | 状态 |
                |---------|------|----------|-------------|-------------|------|
                | [告警1名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                | [告警2名称] | [级别] | [服务名] | [时间] | [时间] | 活跃 |
                
                ---
                
                ## 🔍 告警根因分析1 - [告警名称]
                
                ### 告警详情
                - **告警级别**: [级别]
                - **受影响服务**: [服务名]
                - **持续时间**: [X分钟]
                
                ### 症状描述
                [根据监控指标描述症状]
                
                ### 日志证据
                [引用查询到的关键日志]
                
                ### 根因结论
                [基于证据得出的根本原因]
                
                ---
                
                ## 🛠️ 处理方案执行1 - [告警名称]
                
                ### 已执行的排查步骤
                1. [步骤1]
                2. [步骤2]
                
                ### 处理建议
                [给出具体的处理建议]
                
                ### 预期效果
                [说明预期的效果]
                
                ---
                
                ## 🔍 告警根因分析2 - [告警名称]
                [如果有第2个告警，重复上述格式]
                
                ---
                
                ## 📊 结论
                
                ### 整体评估
                [总结所有告警的整体情况]
                
                ### 关键发现
                - [发现1]
                - [发现2]
                
                ### 后续建议
                1. [建议1]
                2. [建议2]
                
                ### 风险评估
                [评估当前风险等级和影响范围]
                ```
                
                **重要提醒**：
                - 最终输出必须是单个 JSON 对象，不要输出 Markdown 代码围栏或额外解释
                - 服务端会根据 JSON 生成面向运维人员的 Markdown 报告
                - 所有内容必须基于工具查询的真实数据，严禁编造
                - 如果某个步骤失败，在结论中如实说明，不要跳过

                FINISH 输出示例：
                {
                  "status": "CONFIRMED",
                  "faultObject": "app/example-service",
                  "rootCause": "database_connection_exhaustion",
                  "confidence": 0.85,
                  "impact": "服务请求失败率上升",
                  "keyEvidence": [{"toolCallId": "tool-1", "tool": "queryLogs", "finding": "连接池耗尽"}],
                  "recommendedActions": [{"priority": "P1", "action": "检查并扩容连接池", "risk": "需要配置变更"}]
                }
                
                """;
    }

    /**
     * 构建 Executor Agent 系统提示词
     */
    private String buildExecutorPrompt() {
        return """
                你是 Executor Agent，负责读取 Planner 最新输出 {planner_plan}，只执行其中的第一步。
                - 确认步骤所需的工具与参数，尤其是 region 参数要使用连字符格式（ap-guangzhou）；若 Planner 未给出则使用默认区域。
                - 调用相应的工具并收集结果。如工具返回 success=false，需要记录失败原因、请求参数、attempts 和 traceId；该调用已完成代码级重试，不得以相同参数再次调用，应返回 FAILED 并建议 Planner 切换排查方向。空数据不是失败，应如实记录。
                - 如果工具返回 code=MAX_STEPS_EXCEEDED，立即返回当前已有证据，不再请求工具。
                - 将日志、指标、文档等证据整理成结构化摘要，标注对应的告警名称或资源，方便 Planner 填充"告警根因分析 / 处理方案执行"章节。
                - 以 JSON 形式返回执行状态、证据以及给 Planner 的建议，写入 executor_feedback，严禁编造未实际查询到的内容。只有成功工具调用的返回内容才可作为证据。


                输出示例：
                {
                  "status": "SUCCESS",
                  "summary": "近1小时未见 error 日志，仅有 info",
                  "evidence": "...",
                  "nextHint": "建议转向高占用进程"
                }
                """;
    }

    /**
     * 构建 Supervisor Agent 系统提示词
     */
    private String buildSupervisorSystemPrompt() {
        return """
                你是 AI Ops Supervisor，负责调度 planner_agent 与 executor_agent：
                1. 当需要拆解任务或重新制定策略时，调用 planner_agent。
                2. 当 planner_agent 输出 decision=EXECUTE 时，调用 executor_agent 执行第一步。
                3. 根据 executor_agent 的反馈，评估是否需要再次调用 planner_agent，直到 decision=FINISH。
                3.1 路由状态机：首次调用 planner_agent；Planner 给出 EXECUTE 后调用 executor_agent；Executor 返回后最多再调用一次 planner_agent。若 Planner 已输出结构化诊断、decision=FINISH、已有足够证据，必须直接 FINISH。
                3.2 禁止连续两次在没有新增 Executor 证据时调用 planner_agent；没有新增证据时必须 FINISH，不能循环重规划。
                4. FINISH 后，确保 Planner 输出包含 status、faultObject、rootCause、confidence、impact、keyEvidence、recommendedActions 的单个 JSON 对象；服务端负责生成《告警分析报告》。
                5. 若步骤涉及腾讯云日志/主题工具，请确保使用连字符区域 ID（ap-guangzhou 等），或省略 region 以采用默认值。
                6. 如果 Executor 返回工具 success=false，必须停止该工具的同参数重复调用，改用其他证据源；若关键证据均不可用，直接输出"任务无法完成"的报告，明确列出 error code、attempts 和 traceId，严禁凭空编造结果。

                最终 JSON 不得使用 Markdown 代码围栏，也不得附带 JSON 之外的解释文本。

                只允许在 planner_agent、executor_agent 与 FINISH 之间做出选择。

                """;
    }

    Map<String, String> defaultPromptConfig() {
        return Map.of(
                "planner", buildPlannerPrompt(),
                "executor", buildExecutorPrompt(),
                "supervisor", buildSupervisorSystemPrompt());
    }

    private String promptOrDefault(Map<String, String> prompts, String key, String fallback) {
        if (prompts == null) return fallback;
        String value = prompts.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
