package org.example.service;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.aiops.AiOpsRunContext;
import org.example.common.aiops.AiOpsRunResult;
import org.example.common.aiops.AiOpsRunMode;
import org.example.common.aiops.AiOpsRunTrace;
import org.example.common.aiops.AiOpsToolProvider;
import org.example.common.aiops.DiagnosisResult;
import org.example.common.aiops.LiveAiOpsToolProvider;
import org.example.common.aiops.MarkdownReportRenderer;
import org.example.common.aiops.ReplayAiOpsToolProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;

/**
 * 统一 AIOps 运行服务。生产和测评接口都应通过此服务进入相同的 Agent 内核。
 */
@Service
public class AiOpsRunService {
    private final AiOpsService aiOpsService;
    private final AiOpsToolProvider liveToolProvider;
    private final AiOpsToolProvider replayToolProvider;
    private final MarkdownReportRenderer reportRenderer;
    private final ObjectMapper objectMapper;

    public AiOpsRunService(AiOpsService aiOpsService,
            LiveAiOpsToolProvider liveToolProvider,
            ReplayAiOpsToolProvider replayToolProvider,
            MarkdownReportRenderer reportRenderer,
            ObjectMapper objectMapper) {
        this.aiOpsService = aiOpsService;
        this.liveToolProvider = liveToolProvider;
        this.replayToolProvider = replayToolProvider;
        this.reportRenderer = reportRenderer;
        this.objectMapper = objectMapper;
    }

    public AiOpsRunResult run(DashScopeChatModel chatModel, AiOpsRunContext context)
            throws Exception {
        ToolCallback[] toolCallbacks = providerFor(context).getToolCallbacks(context);
        AiOpsRunTrace trace = new AiOpsRunTrace(context.getRunId());
        ToolCallback[] tracedCallbacks = wrapToolCallbacks(toolCallbacks, trace);
        Optional<OverAllState> state = aiOpsService.executeAiOpsAnalysis(chatModel, tracedCallbacks, context);
        if (state.isEmpty()) {
            throw new IllegalStateException("多 Agent 编排未获取到有效结果");
        }

        String rawReport = aiOpsService.extractFinalReport(state.get()).orElse("");
        DiagnosisResult diagnosis = parseDiagnosis(context, rawReport);
        trace.setFinishedAt(java.time.Instant.now());
        return new AiOpsRunResult(context, diagnosis, reportRenderer.render(diagnosis), trace);
    }

    private AiOpsToolProvider providerFor(AiOpsRunContext context) {
        if (context != null && context.getMode() == AiOpsRunMode.REPLAY) {
            return replayToolProvider;
        }
        return liveToolProvider;
    }

    private ToolCallback[] wrapToolCallbacks(ToolCallback[] callbacks, AiOpsRunTrace trace) {
        if (callbacks == null) {
            return new ToolCallback[0];
        }
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new TracingToolCallback(callbacks[i], trace);
        }
        return wrapped;
    }

    private static final class TracingToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final AiOpsRunTrace trace;

        private TracingToolCallback(ToolCallback delegate, AiOpsRunTrace trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return delegate.getToolDefinition();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return delegate.getToolMetadata();
        }

        @Override
        public String call(String toolInput) {
            return invoke(toolInput, null);
        }

        @Override
        public String call(String toolInput, ToolContext toolContext) {
            return invoke(toolInput, toolContext);
        }

        private String invoke(String toolInput, ToolContext toolContext) {
            AiOpsRunTrace.ToolCall call = new AiOpsRunTrace.ToolCall();
            call.setToolCallId("tool-" + UUID.randomUUID());
            call.setToolName(delegate.getToolDefinition().name());
            call.setInput(toolInput);
            long start = System.nanoTime();
            try {
                String output = toolContext == null
                        ? delegate.call(toolInput)
                        : delegate.call(toolInput, toolContext);
                call.setOutput(output);
                call.setSuccess(true);
                return output;
            } catch (RuntimeException exception) {
                call.setSuccess(false);
                call.setError(exception.getMessage());
                throw exception;
            } finally {
                call.setDurationMs((System.nanoTime() - start) / 1_000_000L);
                synchronized (trace.getToolCalls()) {
                    trace.getToolCalls().add(call);
                }
            }
        }
    }

    private DiagnosisResult parseDiagnosis(AiOpsRunContext context, String rawReport) {
        DiagnosisResult result = new DiagnosisResult();
        result.setRunId(context.getRunId());
        result.setMode(context.getMode() == null ? AiOpsRunMode.LIVE : context.getMode());
        result.setRawReport(rawReport);

        try {
            JsonNode root = objectMapper.readTree(stripMarkdownFence(rawReport));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("最终结果不是 JSON 对象");
            }
            JsonNode diagnosisNode = root.has("diagnosis") && root.get("diagnosis").isObject()
                    ? root.get("diagnosis") : root;
            result.setStatus(text(diagnosisNode, "status", "CONFIRMED"));
            result.setFaultObject(text(diagnosisNode, "faultObject", null));
            result.setRootCause(text(diagnosisNode, "rootCause", null));
            result.setImpact(text(diagnosisNode, "impact", null));
            if (diagnosisNode.has("confidence") && diagnosisNode.get("confidence").isNumber()) {
                result.setConfidence(diagnosisNode.get("confidence").doubleValue());
            }

            JsonNode evidence = diagnosisNode.get("keyEvidence");
            if (evidence != null && evidence.isArray()) {
                for (JsonNode item : evidence) {
                    DiagnosisResult.Evidence target = new DiagnosisResult.Evidence();
                    target.setToolCallId(text(item, "toolCallId", null));
                    target.setTool(text(item, "tool", null));
                    target.setFinding(text(item, "finding", null));
                    result.getKeyEvidence().add(target);
                }
            }

            JsonNode actions = diagnosisNode.get("recommendedActions");
            if (actions != null && actions.isArray()) {
                for (JsonNode item : actions) {
                    DiagnosisResult.RecommendedAction target = new DiagnosisResult.RecommendedAction();
                    target.setPriority(text(item, "priority", null));
                    target.setAction(text(item, "action", null));
                    target.setRisk(text(item, "risk", null));
                    result.getRecommendedActions().add(target);
                }
            }
            validateDiagnosis(result);
        } catch (Exception exception) {
            result.setStatus("INVALID_OUTPUT");
            result.setParseError(exception.getMessage());
        }
        return result;
    }

    private void validateDiagnosis(DiagnosisResult result) {
        if (isBlank(result.getStatus()) || isBlank(result.getFaultObject())
                || isBlank(result.getRootCause()) || isBlank(result.getImpact())
                || result.getConfidence() == null || result.getKeyEvidence() == null
                || result.getRecommendedActions() == null) {
            throw new IllegalArgumentException("结构化诊断缺少必填字段");
        }
        if (result.getConfidence() < 0 || result.getConfidence() > 1) {
            throw new IllegalArgumentException("confidence 必须位于 0 到 1 之间");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String stripMarkdownFence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            int lastFence = text.lastIndexOf("```");
            if (firstLineEnd >= 0 && lastFence > firstLineEnd) {
                return text.substring(firstLineEnd + 1, lastFence).trim();
            }
        }
        return text;
    }

    private String text(JsonNode node, String field, String fallback) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }
}
