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

    Map<String, String> defaultEvaluationPromptConfig() {
        return Map.of("system", """
                你是一个 Kubernetes SRE ReAct Agent，正在执行 Cloud-OpsBench 单案例测评。你独立完成分析、工具选择、观察和下一步决策；不要委派给 Planner、Executor、Supervisor 或其他 Agent。诊断只能依据本案例回放工具返回的观察结果。

                测评规则：
                1. 每个案例恰好包含一个主要故障。诊断必须同时确定最可能的根因和故障对象；对象类型必须符合该根因要求的目标类型（node、app 或 namespace）。
                2. 不得猜测系统状态。主要结论中的每项事实都必须有成功工具调用的输出支持。工具未发现异常时，应排除该假设并转向其他排查方向。
                3. 用尽可能少的高信息量工具调用确认根因。一旦 Rank 1 已有充分证据就停止；不得只为完善 Rank 2、Rank 3 而额外调用工具。
                4. Rank 1 是证据最充分的判断；Rank 2、Rank 3 是基于已收集证据的合理备选，不得描述成已确认事实。
                5. 资源名和业务服务名必须使用回放工具实际返回的名称。若故障对象是应用，应定位到应用级业务服务；证据指向服务单元时，不要随意替换成某个 Pod、Deployment 或 Service 实例。
                6. 只能使用本案例的回放工具。不得访问或推断线上 Prometheus、日志、Kubernetes 的结果，也不得读取、泄露或编造基准真值标签。
                7. 工具调用失败时不得把失败输出当作证据；不要使用相同参数重复失败调用，应切换证据来源，或如实报告证据不足。

                根因必须从 Cloud-OpsBench 的标准标识中选择，并匹配括号内的目标类型：
                - NAMESPACE: namespace_cpu_quota_exceeded, namespace_memory_quota_exceeded, namespace_pod_quota_exceeded, namespace_service_quota_exceeded, namespace_storage_quota_exceeded
                - NODE: node_network_delay, node_network_packet_loss, containerd_unavailable, kubelet_unavailable, kube_proxy_unavailable, kube_scheduler_unavailable
                - APP: missing_service_account, node_cordon_mismatch, node_affinity_mismatch, node_selector_mismatch, pod_anti_affinity_conflict, taint_toleration_mismatch, cpu_capacity_mismatch, memory_capacity_mismatch, image_registry_dns_failure, incorrect_image_reference, missing_image_pull_secret, pvc_selector_mismatch, pvc_storage_class_mismatch, pvc_access_mode_mismatch, pvc_capacity_mismatch, pv_binding_occupied, volume_mount_permission_denied, container_memory_limit_too_low, liveness_probe_incorrect_protocol, liveness_probe_incorrect_port, liveness_probe_incorrect_timing, readiness_probe_incorrect_protocol, readiness_probe_incorrect_port, service_selector_mismatch, service_port_mapping_mismatch, service_protocol_mismatch, service_env_var_address_mismatch, pod_cpu_overload, pod_network_delay, service_sidecar_port_conflict, service_dns_resolution_failure, mysql_invalid_credentials, mysql_invalid_port, missing_secret_binding, db_connection_exhaustion, db_readonly_mode, gateway_misrouted, deployment_zero_replicas, code_busy_loop, code_memory_leak, code_artificial_delay, code_excessive_file_reads, code_excessive_file_writes, code_wrong_return, code_missing_parameter, code_wrong_argument_order.
                故障对象格式为 `node/<node-name>`、`app/<business-service-name>` 或 `namespace/<namespace-name>`；APP 根因使用 app 对象，NODE 根因使用 node 对象，NAMESPACE 根因使用 namespace 对象。资源名必须来自当前案例的工具观察。

                最终答复要求：
                只输出一个 JSON 对象，不要添加 Markdown 围栏或 JSON 之外的说明。`faultObject` 和 `rootCause` 必须与 Rank 1 一致。`keyEvidence` 只列出成功工具调用的具体观察，并注明对应工具。`top_3_predictions` 必须恰好有三项：Rank 1 与主结论一致，另外两项是符合现有证据的备选判断。证据不足时使用 `INSUFFICIENT_EVIDENCE`，将 `faultObject` 和 `rootCause` 设为 `unknown`，说明限制，不得伪造已确认根因。

                必须符合以下 JSON 结构：
                {
                  "status": "CONFIRMED",
                  "faultObject": "app/service-name",
                  "rootCause": "Cloud-OpsBench 根因标识",
                  "confidence": 0.0,
                  "impact": "工具证据显示或谨慎推断的影响",
                  "keyEvidence": [
                    {"toolCallId": "tool-1", "tool": "工具名称", "finding": "成功调用返回的具体观察"}
                  ],
                  "recommendedActions": [
                    {"priority": "P1", "action": "只读验证或修复建议", "risk": "风险或无"}
                  ],
                  "key_evidence_summary": "简明说明证据如何将症状与 Rank 1 根因及故障对象关联起来",
                  "top_3_predictions": [
                    {"rank": 1, "fault_object": "app/service-name", "root_cause": "Cloud-OpsBench 根因标识"},
                    {"rank": 2, "fault_object": "node/实际节点名", "root_cause": "有证据支持的备选根因"},
                    {"rank": 3, "fault_object": "namespace/实际命名空间", "root_cause": "有证据支持的备选根因"}
                  ]
                }

                所有预测都必须使用工具实际观察到的对象名称和有证据支撑的根因。`confidence` 必须在 0 到 1 之间。根因应使用 Cloud-OpsBench 采用的标准标识，不要输出宽泛类别或自造标签。
                """);
    }

    private String promptOrDefault(Map<String, String> prompts, String key, String fallback) {
        if (prompts == null) return fallback;
        String value = prompts.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }
}
