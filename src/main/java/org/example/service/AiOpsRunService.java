package org.example.service;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    public AiOpsRunResult run(ChatModel chatModel, AiOpsRunContext context)
            throws Exception {
        return run(chatModel, context, java.util.Map.of());
    }

    public AiOpsRunResult run(ChatModel chatModel, AiOpsRunContext context,
            java.util.Map<String, String> prompts) throws Exception {
        ToolCallback[] toolCallbacks = providerFor(context).getToolCallbacks(context);
        AiOpsRunTrace trace = new AiOpsRunTrace(context.getRunId());
        AiOpsStepBudget stepBudget = new AiOpsStepBudget(context == null ? 20 : context.getMaxSteps());
        ToolCallback[] tracedCallbacks = wrapToolCallbacks(toolCallbacks, trace, stepBudget);
        Optional<OverAllState> state = aiOpsService.executeAiOpsAnalysis(chatModel, tracedCallbacks, context, prompts);
        if (state.isEmpty()) {
            throw new IllegalStateException("AIOps ReAct Agent 未返回有效结果");
        }

        String rawReport = aiOpsService.extractFinalReport(state.get()).orElse("");
        DiagnosisResult diagnosis = parseDiagnosis(context, rawReport, trace);
        trace.setFinishedAt(java.time.Instant.now());
        return new AiOpsRunResult(context, diagnosis, reportRenderer.render(diagnosis), trace);
    }

    private AiOpsToolProvider providerFor(AiOpsRunContext context) {
        if (context != null && context.getMode() == AiOpsRunMode.REPLAY) {
            return replayToolProvider;
        }
        return liveToolProvider;
    }

    private ToolCallback[] wrapToolCallbacks(ToolCallback[] callbacks, AiOpsRunTrace trace,
            AiOpsStepBudget stepBudget) {
        if (callbacks == null) {
            return new ToolCallback[0];
        }
        ToolCallback[] wrapped = new ToolCallback[callbacks.length];
        for (int i = 0; i < callbacks.length; i++) {
            wrapped[i] = new TracingToolCallback(callbacks[i], trace, stepBudget);
        }
        return wrapped;
    }

    private final class TracingToolCallback implements ToolCallback {
        private final ToolCallback delegate;
        private final AiOpsRunTrace trace;
        private final AiOpsStepBudget stepBudget;

        private TracingToolCallback(ToolCallback delegate, AiOpsRunTrace trace, AiOpsStepBudget stepBudget) {
            this.delegate = delegate;
            this.trace = trace;
            this.stepBudget = stepBudget;
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
                if (!stepBudget.tryAcquire()) {
                    String output = stepLimitError();
                    call.setOutput(output);
                    call.setSuccess(false);
                    call.setError("已达到 maxSteps=" + stepBudget.maxSteps());
                    return output;
                }
                String output = toolContext == null
                        ? delegate.call(toolInput)
                        : delegate.call(toolInput, toolContext);
                call.setOutput(output);
                if (isToolFailure(output)) {
                    call.setSuccess(false);
                    call.setError(extractToolError(output));
                } else {
                    call.setSuccess(true);
                }
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

        private boolean isToolFailure(String output) {
            if (output == null || output.isBlank()) {
                return true;
            }
            try {
                JsonNode node = objectMapper.readTree(output);
                if (node != null && node.isObject() && node.has("success")
                        && node.get("success").isBoolean()) {
                    return !node.get("success").asBoolean();
                }
            } catch (Exception ignored) {
                // 兼容真实工具的纯文本结果；仅将明确的错误前缀视为失败。
            }
            String normalized = output.trim().toLowerCase();
            return normalized.startsWith("error:")
                    || normalized.startsWith("error from")
                    || normalized.startsWith("an unexpected error")
                    || normalized.startsWith("错误:")
                    || normalized.contains("回放数据中没有匹配的工具调用");
        }

        private String extractToolError(String output) {
            try {
                JsonNode node = objectMapper.readTree(output);
                if (node != null && node.isObject() && node.has("message")) {
                    return node.get("message").asText();
                }
            } catch (Exception ignored) {
                // 使用原始结果作为错误上下文。
            }
            return output;
        }

        private String stepLimitError() {
            ObjectNode result = objectMapper.createObjectNode();
            result.put("success", false);
            result.put("code", "MAX_STEPS_EXCEEDED");
            result.put("message", "已达到本次运行的 maxSteps 限制，禁止继续调用工具，请输出最终诊断。");
            return result.toString();
        }
    }

    private static final class AiOpsStepBudget {
        private final int maxSteps;
        private final java.util.concurrent.atomic.AtomicInteger used = new java.util.concurrent.atomic.AtomicInteger();

        private AiOpsStepBudget(int maxSteps) {
            this.maxSteps = Math.max(1, maxSteps);
        }

        private boolean tryAcquire() {
            while (true) {
                int current = used.get();
                if (current >= maxSteps) {
                    return false;
                }
                if (used.compareAndSet(current, current + 1)) {
                    return true;
                }
            }
        }

        private int maxSteps() {
            return maxSteps;
        }
    }

    private DiagnosisResult parseDiagnosis(AiOpsRunContext context, String rawReport, AiOpsRunTrace trace) {
        DiagnosisResult result = new DiagnosisResult();
        result.setRunId(context.getRunId());
        result.setMode(context.getMode() == null ? AiOpsRunMode.LIVE : context.getMode());
        result.setRawReport(rawReport);

        try {
            // Qwen 兼容层会把历史工具消息序列化为文本。最终答案可能因此被包在
            // “[历史工具结果] ... {JSON}” 中，不能再要求整段文本从第一个字符就是 JSON。
            JsonNode root = extractDiagnosisJson(rawReport);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("最终结果不是 JSON 对象");
            }
            JsonNode diagnosisNode = root.has("diagnosis") && root.get("diagnosis").isObject()
                    ? root.get("diagnosis") : root;
            result.setStatus(text(diagnosisNode, "status", "UNCONFIRMED"));
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
            validateEvidence(result, trace);
        } catch (Exception exception) {
            result.setStatus(exception instanceof EvidenceValidationException
                    ? "EVIDENCE_UNVERIFIED" : "INVALID_OUTPUT");
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
        if ("CONFIRMED".equalsIgnoreCase(result.getStatus()) && result.getKeyEvidence().isEmpty()) {
            throw new IllegalArgumentException("CONFIRMED 诊断至少需要一条 keyEvidence");
        }
    }

    private void validateEvidence(DiagnosisResult result, AiOpsRunTrace trace) {
        Set<String> usedCallIds = new HashSet<>();
        for (DiagnosisResult.Evidence evidence : result.getKeyEvidence()) {
            AiOpsRunTrace.ToolCall call = resolveToolCall(evidence, trace, usedCallIds);
            if (call == null) {
                throw new EvidenceValidationException("证据未能关联到本次运行中的工具调用: " + evidence.getToolCallId());
            }
            if (!call.isSuccess()) {
                throw new EvidenceValidationException("证据关联到了失败的工具调用: " + call.getToolCallId());
            }
            // 将 tool-1 这类模型序号归一化成真实 trace ID，供评测和 Markdown 报告关联。
            evidence.setToolCallId(call.getToolCallId());
            usedCallIds.add(call.getToolCallId());
            if (isBlank(evidence.getFinding())) {
                throw new EvidenceValidationException("keyEvidence.finding 不能为空");
            }
        }
    }

    private AiOpsRunTrace.ToolCall resolveToolCall(DiagnosisResult.Evidence evidence,
            AiOpsRunTrace trace, Set<String> usedCallIds) {
        String requestedId = evidence == null ? null : evidence.getToolCallId();
        if (trace == null || trace.getToolCalls() == null) {
            return null;
        }
        synchronized (trace.getToolCalls()) {
            // 优先采用真实 trace ID。
            if (!isBlank(requestedId)) {
                for (AiOpsRunTrace.ToolCall call : trace.getToolCalls()) {
                    if (requestedId.equals(call.getToolCallId())) {
                        return call;
                    }
                }
            }
            // 兼容模型按序号输出的 tool-1、tool-2。
            if (!isBlank(requestedId) && requestedId.matches("tool-[0-9]+")) {
                try {
                    int index = Integer.parseInt(requestedId.substring("tool-".length())) - 1;
                    if (index >= 0 && index < trace.getToolCalls().size()) {
                        return trace.getToolCalls().get(index);
                    }
                } catch (NumberFormatException ignored) {
                    // 继续尝试按工具名和证据内容匹配。
                }
            }

            // 兼容 Qwen/兼容层生成的 call_xxx ID。该 ID 不在本次 trace 中，
            // 但证据通常同时带有工具名和发现内容，按成功调用进行确定性归一化。
            AiOpsRunTrace.ToolCall best = null;
            int bestScore = 0;
            for (AiOpsRunTrace.ToolCall call : trace.getToolCalls()) {
                if (!call.isSuccess() || usedCallIds.contains(call.getToolCallId())
                        || !sameTool(evidence.getTool(), call.getToolName())) {
                    continue;
                }
                int score = evidenceMatchScore(evidence.getFinding(), call.getOutput());
                if (score > bestScore) {
                    best = call;
                    bestScore = score;
                }
            }
            if (best != null) {
                return best;
            }

            // 最后允许同名成功调用兜底，仍然禁止关联失败调用。
            for (AiOpsRunTrace.ToolCall call : trace.getToolCalls()) {
                if (call.isSuccess() && !usedCallIds.contains(call.getToolCallId())
                        && sameTool(evidence.getTool(), call.getToolName())) {
                    return call;
                }
            }
        }
        return null;
    }

    private boolean sameTool(String left, String right) {
        return !isBlank(left) && !isBlank(right) && left.trim().equalsIgnoreCase(right.trim());
    }

    private int evidenceMatchScore(String finding, String output) {
        if (isBlank(finding) || isBlank(output)) {
            return 0;
        }
        String normalizedOutput = output.toLowerCase();
        int score = 0;
        for (String token : finding.toLowerCase().split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 3 && normalizedOutput.contains(token)) {
                score++;
            }
        }
        return score;
    }

    /**
     * 从纯 JSON、Markdown JSON 或带有历史工具文本的回答中提取最终诊断对象。
     * 采用平衡括号扫描，避免简单正则在 JSON 字符串包含大括号时截断内容。
     */
    private JsonNode extractDiagnosisJson(String rawReport) throws Exception {
        String text = stripMarkdownFence(rawReport);
        if (text.isBlank()) {
            throw new IllegalArgumentException("最终结果为空");
        }
        try {
            JsonNode root = objectMapper.readTree(text);
            if (root != null && root.isObject()) {
                return root;
            }
        } catch (Exception ignored) {
            // 继续扫描文本中的 JSON 对象。
        }

        List<JsonNode> candidates = new ArrayList<>();
        for (int start = 0; start < text.length(); start++) {
            if (text.charAt(start) != '{') {
                continue;
            }
            int end = findJsonObjectEnd(text, start);
            if (end < 0) {
                continue;
            }
            try {
                JsonNode candidate = objectMapper.readTree(text.substring(start, end + 1));
                if (candidate != null && candidate.isObject()
                        && (candidate.has("faultObject") || candidate.has("rootCause")
                        || candidate.has("keyEvidence"))) {
                    candidates.add(candidate);
                }
            } catch (Exception ignored) {
                // 当前大括号可能属于普通文本或嵌套对象，继续扫描下一个候选。
            }
        }
        if (!candidates.isEmpty()) {
            // 历史工具结果可能包含多个 JSON，最终诊断通常是最后一个候选。
            return candidates.get(candidates.size() - 1);
        }
        throw new IllegalArgumentException("无法从 Agent 输出中提取结构化 JSON");
    }

    private int findJsonObjectEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (ch == '\\') {
                    escaped = true;
                } else if (ch == '"') {
                    inString = false;
                }
                continue;
            }
            if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static final class EvidenceValidationException extends IllegalArgumentException {
        private EvidenceValidationException(String message) {
            super(message);
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
