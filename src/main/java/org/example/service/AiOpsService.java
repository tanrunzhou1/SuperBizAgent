package org.example.service;

import com.alibaba.cloud.ai.graph.CompileConfig;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Executes LIVE and REPLAY diagnostics with one ReAct agent and a mode-specific tool set. */
@Service
public class AiOpsService {

    private static final Logger logger = LoggerFactory.getLogger(AiOpsService.class);

    @Autowired
    private DateTimeTools dateTimeTools;

    @Autowired
    private InternalDocsTools internalDocsTools;

    @Autowired
    private QueryMetricsTools queryMetricsTools;

    @Autowired(required = false)
    private QueryLogsTools queryLogsTools;

    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks)
            throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks,
                AiOpsRunContext.live("请读取当前活跃告警并执行故障分析。"));
    }

    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks,
            AiOpsRunContext runContext) throws GraphRunnerException {
        return executeAiOpsAnalysis(chatModel, toolCallbacks, runContext, Map.of());
    }

    public Optional<OverAllState> executeAiOpsAnalysis(ChatModel chatModel, ToolCallback[] toolCallbacks,
            AiOpsRunContext runContext, Map<String, String> prompts) throws GraphRunnerException {
        AiOpsRunContext effectiveContext = runContext == null
                ? AiOpsRunContext.live("请读取当前活跃告警并执行故障分析。") : runContext;
        int maxSteps = Math.max(1, effectiveContext.getMaxSteps());
        ReactAgent agent = ReactAgent.builder()
                .name("ai_ops_react_agent")
                .description("读取告警和运行证据，按 ReAct 循环调用工具并输出根因分析报告")
                .model(chatModel)
                .systemPrompt(promptOrDefault(prompts, "system", buildSystemPrompt()))
                .methodTools(buildMethodToolsArray(effectiveContext))
                .tools(toolCallbacks == null ? new ToolCallback[0] : toolCallbacks)
                .compileConfig(CompileConfig.builder().recursionLimit(Math.max(20, maxSteps * 10)).build())
                .outputKey("aiops_report")
                .build();

        logger.info("开始执行单 ReAct AIOps Agent, mode={}, maxSteps={}",
                effectiveContext.getMode(), maxSteps);
        return agent.invoke(effectiveContext.taskPrompt());
    }

    public Optional<String> extractFinalReport(OverAllState state) {
        if (state == null) return Optional.empty();
        List<String> candidates = new ArrayList<>();
        collectReportCandidate(state.data().get("aiops_report"), candidates);
        collectReportCandidate(state.data().get("messages"), candidates);
        for (String candidate : candidates) {
            if (candidate.contains("faultObject") && candidate.contains("rootCause")) {
                return Optional.of(candidate);
            }
        }
        return candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(candidates.size() - 1));
    }

    private void collectReportCandidate(Object value, List<String> candidates) {
        if (value instanceof AssistantMessage assistantMessage) {
            if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
                candidates.add(assistantMessage.getText());
            }
        } else if (value instanceof Message message) {
            if (message.getText() != null && !message.getText().isBlank()) candidates.add(message.getText());
        } else if (value instanceof Collection<?> collection) {
            for (Object item : collection) collectReportCandidate(item, candidates);
        } else if (value instanceof String text && !text.isBlank()) {
            candidates.add(text);
        }
    }

    private Object[] buildMethodToolsArray(AiOpsRunContext runContext) {
        // REPLAY may only use its case-scoped ToolCallbacks, never production integrations.
        if (runContext.getMode() == org.example.common.aiops.AiOpsRunMode.REPLAY) return new Object[0];
        if (queryLogsTools != null) {
            return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools, queryLogsTools};
        }
        return new Object[]{dateTimeTools, internalDocsTools, queryMetricsTools};
    }

    private String buildSystemPrompt() {
        return """
                你是一个负责线上故障排查与案例回放的 SRE ReAct Agent。整个任务由你一个 Agent 完成：你自己分析、选择工具、观察结果、调整下一步并给出结论；不要委派给 Planner、Executor、Supervisor 或其他 Agent。

                工作方式：
                1. 每次运行都必须先读取当前活跃告警；用户提供的 incidentPrompt 是补充线索，不得替代告警读取。REPLAY 模式严格使用案例回放工具。
                2. 根据观察到的告警和工具结果，选择最有价值的下一步工具；工具返回后先核对证据，再决定是否继续。不要重复相同参数的失败调用。
                3. 只把工具成功返回的数据作为证据。不得编造告警、日志、指标、时间或资源信息；证据不足时明确说明。
                4. 达到 maxSteps 或工具返回 MAX_STEPS_EXCEEDED 时立即停止工具调用，并基于已有证据给出结论。
                5. 腾讯云日志/主题工具的 region 使用连字符格式（如 ap-guangzhou）；没有把握时省略 region 使用默认值。
                6. LIVE 只做只读排查。禁止执行变更或修复操作。

                最终答复必须是一个 JSON 对象，不要使用 Markdown 围栏或 JSON 之外的说明。必须包含：
                {"status":"CONFIRMED|INSUFFICIENT_EVIDENCE","faultObject":"受影响对象或未知","rootCause":"根因或未知","confidence":0.0,"impact":"影响","keyEvidence":[{"toolCallId":"调用标识","tool":"工具名","finding":"工具实际返回的证据"}],"recommendedActions":[{"priority":"P1","action":"建议","risk":"风险"}]}

                keyEvidence 只包含成功工具调用的真实发现；没有证据时使用空数组。对工具失败应说明工具名、错误信息及无法继续的原因，不得把失败结果包装成根因证据。confidence 范围为 0 到 1。
                """;
    }

    Map<String, String> defaultPromptConfig() {
        return Map.of("system", buildSystemPrompt());
    }

    private String promptOrDefault(Map<String, String> prompts, String key, String fallback) {
        if (prompts == null) return fallback;
        String value = prompts.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
