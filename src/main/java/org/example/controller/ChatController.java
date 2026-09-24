package org.example.controller;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import lombok.Getter;
import lombok.Setter;
import org.example.common.aiops.AiOpsRunMode;
import org.example.common.api.ApiError;
import org.example.common.api.ApiResponse;
import org.example.common.api.ChatSessionContext;
import org.example.common.api.SseMessage;
import org.example.common.api.TraceIdContext;
import org.example.common.aiops.AiOpsRunContext;
import org.example.common.aiops.AiOpsRunResult;
import org.example.common.exception.AppException;
import org.example.common.exception.BusinessException;
import org.example.common.exception.ErrorCode;
import org.example.entity.ChatMessageEntity;
import org.example.service.ChatService;
import org.example.service.ChatSessionService;
import org.example.service.AiOpsRunService;
import org.example.service.AgentProfileService;
import org.example.service.SessionExecutionGuard;
import org.example.dto.AIOpsRequest;
import org.example.dto.AIOpsResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一 API 控制器
 * 适配前端接口需求
 */
@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private ChatService chatService;

    @Autowired
    private AiOpsRunService aiOpsRunService;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private AgentProfileService agentProfileService;

    @Autowired
    private SessionExecutionGuard sessionExecutionGuard;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 普通对话接口（支持工具调用）
     * 与 /chat_react 逻辑一致，但直接返回完整结果而非流式输出
     */
    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "问题内容不能为空");
        }

        String sessionId = chatSessionService.getOrCreateSession(request.getId());
        if (!sessionExecutionGuard.tryAcquire(sessionId)) {
            throw new BusinessException(ErrorCode.SESSION_TASK_IN_PROGRESS, "当前会话正在执行 AIOps，请等待完成后再发送新消息");
        }
        ChatSessionContext.set(sessionId);
        try {
        List<Map<String, String>> history = chatSessionService.loadHistory(sessionId);
        logger.info("会话历史消息数: {}", history.size());

        AgentProfileService.ProfileSnapshot profile = agentProfileService.getActive(AgentProfileService.CHAT);
        ChatModel chatModel = chatService.createChatModel(profile.sampling());

        chatService.logAvailableTools();

        logger.info("开始 ReactAgent 对话（支持自动工具调用）");
        String systemPrompt = chatService.buildSystemPrompt(history, profile.prompts().get("system"));
        ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
        String fullAnswer = chatService.executeChat(agent, request.getQuestion());
        chatSessionService.saveTurn(sessionId, request.getQuestion(), fullAnswer, TraceIdContext.getOrCreate());
        logger.info("已更新会话历史 - SessionId: {}", sessionId);
            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));
        } finally {
            ChatSessionContext.clear();
            sessionExecutionGuard.release(sessionId);
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ClearRequest request) {
        logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

        if (request.getId() == null || request.getId().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "会话ID不能为空");
        }

        if (chatSessionService.findSession(request.getId()).isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }
        if (sessionExecutionGuard.isActive(request.getId())) {
            throw new BusinessException(ErrorCode.SESSION_TASK_IN_PROGRESS, "当前会话正在执行任务，暂时不能清空记录");
        }
        chatSessionService.clear(request.getId());
        return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
    }

    /**
     * ReactAgent 对话接口（SSE 流式模式，支持多轮对话，支持自动工具调用，例如获取当前时间，查询日志，告警等）
     * 支持 session 管理，保留对话历史
     */
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时
        String traceId = TraceIdContext.getOrCreate();

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                sendSseError(emitter, new BusinessException(ErrorCode.INVALID_REQUEST, "问题内容不能为空"));
                emitter.complete();
            } catch (IOException e) {
                emitter.complete();
            }
            return emitter;
        }

        executor.execute(() -> {
            String sessionId = null;
            boolean acquired = false;
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                sessionId = chatSessionService.getOrCreateSession(request.getId());
                if (!sessionExecutionGuard.tryAcquire(sessionId)) {
                    sendSseError(emitter, new BusinessException(ErrorCode.SESSION_TASK_IN_PROGRESS,
                            "当前会话正在执行 AIOps，请等待完成后再发送新消息"));
                    emitter.complete();
                    return;
                }
                acquired = true;
                final String activeSessionId = sessionId;
                List<Map<String, String>> history = chatSessionService.loadHistory(sessionId);
                ChatSessionContext.set(sessionId);
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                AgentProfileService.ProfileSnapshot profile = agentProfileService.getActive(AgentProfileService.CHAT);
                ChatModel chatModel = chatService.createChatModel(profile.sampling());

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");
                
                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history, profile.prompts().get("system"));
                
                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);
                
                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();
                
                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());
                
                stream.subscribe(
                    output -> {
                        try {
                            // 检查是否为 StreamingOutput 类型
                            if (output instanceof StreamingOutput streamingOutput) {
                                OutputType type = streamingOutput.getOutputType();
                                
                                // 处理模型推理的流式输出
                                if (type == OutputType.AGENT_MODEL_STREAMING) {
                                    // 流式增量内容，逐步显示
                                    String chunk = streamingOutput.message().getText();
                                    if (chunk != null && !chunk.isEmpty()) {
                                        fullAnswerBuilder.append(chunk);
                                        
                                        // 实时发送到前端
                                        emitter.send(SseEmitter.event()
                                                .name("message")
                                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                                        
                                        logger.info("发送流式内容: {}", chunk);
                                    }
                                } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                    // 模型推理完成
                                    logger.info("模型输出完成");
                                } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                    // 工具调用完成
                                    logger.info("工具调用完成: {}", output.node());
                                } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                    // Hook 执行完成
                                    logger.debug("Hook 执行完成: {}", output.node());
                                }
                            }
                        } catch (IOException e) {
                            logger.error("发送流式消息失败", e);
                            throw new RuntimeException(e);
                        }
                    },
                    error -> {
                        // 错误处理
                        logger.error("ReactAgent 流式对话失败", error);
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.error(toApiError(error)), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.complete();
                        ChatSessionContext.clear();
                        sessionExecutionGuard.release(activeSessionId);
                    },
                    () -> {
                        // 完成处理
                        try {
                            String fullAnswer = fullAnswerBuilder.toString();
                            logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                activeSessionId, fullAnswer.length());
                            
                            chatSessionService.saveTurn(activeSessionId, request.getQuestion(), fullAnswer, traceId);
                            logger.info("已更新会话历史 - SessionId: {}", activeSessionId);
                            
                            // 发送完成标记
                            emitter.send(SseEmitter.event()
                                    .name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        } finally {
                            ChatSessionContext.clear();
                            sessionExecutionGuard.release(activeSessionId);
                        }
                    }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error(toApiError(e)), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.complete();
                ChatSessionContext.clear();
                if (acquired && sessionId != null) sessionExecutionGuard.release(sessionId);
            }
        });

        return emitter;
    }

    /**
     * AI 智能运维接口（SSE 流式模式）。请求体可选，未提供时读取当前活跃告警。
     */
    @PostMapping("/ai_ops")
    public Object aiOps(@RequestBody(required = false) AIOpsRequest request) {
        AIOpsRequest safeRequest = request == null ? new AIOpsRequest() : request;
        AiOpsRunContext context = safeRequest.toRunContext();
        if (context.getMode() != AiOpsRunMode.LIVE) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "生产接口只允许 LIVE 运行模式");
        }
        return safeRequest.shouldStream() ? startAiOpsStream(context) : runAiOpsSync(context);
    }

    /**
     * 单案例测评接口。当前从 Cloud-OpsBench 冻结快照执行一个 caseId。
     */
    @PostMapping("/ai-ops/evaluations")
    public Object evaluateAiOps(@RequestBody AIOpsRequest request) {
        if (request == null || request.getCaseId() == null || request.getCaseId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "测评接口必须提供 caseId");
        }
        request.setMode(AiOpsRunMode.REPLAY);
        AiOpsRunContext context = request.toRunContext();
        return request.shouldStream() ? startAiOpsStream(context) : runAiOpsSync(context);
    }

    @PostMapping(value = "/sessions/{sessionId}/aiops-runs", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter runSessionAiOps(@PathVariable String sessionId,
            @RequestBody(required = false) SessionAiOpsRequest request) {
        String actualSessionId = chatSessionService.getOrCreateSession(sessionId);
        if (!sessionExecutionGuard.tryAcquire(actualSessionId)) {
            throw new BusinessException(ErrorCode.SESSION_TASK_IN_PROGRESS,
                    "当前会话正在执行 AIOps，请等待完成后再发送新消息");
        }
        SessionAiOpsRequest safe = request == null ? new SessionAiOpsRequest() : request;
        AIOpsRequest aiOpsRequest = new AIOpsRequest();
        aiOpsRequest.setIncidentPrompt(safe.getIncidentPrompt());
        aiOpsRequest.setMaxSteps(safe.getMaxSteps());
        aiOpsRequest.setStream(true);
        AiOpsRunContext context = aiOpsRequest.toRunContext();
        String requestText = safe.getIncidentPrompt() == null || safe.getIncidentPrompt().isBlank()
                ? "基于当前活跃告警进行排查" : safe.getIncidentPrompt().trim();
        String runId = context.getRunId();
        ChatSessionService.AiOpsMessages messages;
        try {
            messages = chatSessionService.beginAiOps(actualSessionId, requestText, runId, TraceIdContext.getOrCreate());
        } catch (RuntimeException exception) {
            sessionExecutionGuard.release(actualSessionId);
            throw exception;
        }
        return streamSessionAiOps(actualSessionId, context, messages);
    }

    @PostMapping(value = "/evaluations/runs", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter runEvaluation(@RequestBody EvaluationRunRequest request) {
        if (request == null || request.getCaseId() == null || request.getCaseId().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "测评请求必须提供 caseId");
        }
        if (request.getDataset() != null && !"cloud-ops-bench".equals(request.getDataset())) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "dataset 仅支持 cloud-ops-bench");
        }
        String profileName = request.getAgentProfile() == null || request.getAgentProfile().isBlank()
                ? AgentProfileService.AIOPS_LIVE : request.getAgentProfile().trim().toUpperCase();
        if (!AgentProfileService.AIOPS_LIVE.equals(profileName)
                && !AgentProfileService.EVALUATION.equals(profileName)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "agentProfile 仅支持 AIOPS_LIVE 或 EVALUATION");
        }
        AIOpsRequest replay = new AIOpsRequest();
        replay.setCaseId(request.getCaseId().trim());
        replay.setMaxSteps(request.getMaxSteps());
        replay.setMode(AiOpsRunMode.REPLAY);
        AgentProfileService.ProfileSnapshot profile = agentProfileService.getVersionOrActive(profileName,
                request.getProfileVersion());
        AiOpsRunContext context = replay.toRunContext();
        SseEmitter emitter = new SseEmitter(600000L);
        executor.execute(() -> {
            try {
                streamRun(emitter, runAiOps(context, profile));
            } catch (Exception exception) {
                logger.error("Evaluation run failed, runId={}, caseId={}", context.getRunId(), context.getCaseId(), exception);
                sendRunFailure(emitter, exception);
            }
        });
        return emitter;
    }

    private SseEmitter streamSessionAiOps(String sessionId, AiOpsRunContext context,
            ChatSessionService.AiOpsMessages messages) {
        SseEmitter emitter = new SseEmitter(600000L);
        executor.execute(() -> {
            ChatSessionContext.set(sessionId);
            try {
                AgentProfileService.ProfileSnapshot profile = agentProfileService.getActive(AgentProfileService.AIOPS_LIVE);
                AiOpsRunResult result = runAiOps(context, profile);
                chatSessionService.finishAiOps(sessionId, context.getRunId(), result.getMarkdownReport(), true);
                streamRun(emitter, result);
            } catch (Exception exception) {
                logger.error("Session AIOps failed, sessionId={}, runId={}", sessionId, context.getRunId(), exception);
                String failureReport = "## AIOps 排查失败\n\n" + safeMessage(exception);
                try {
                    chatSessionService.finishAiOps(sessionId, context.getRunId(), failureReport, false);
                } catch (Exception persistException) {
                    logger.error("Unable to persist AIOps failure result", persistException);
                }
                sendRunFailure(emitter, exception);
            } finally {
                ChatSessionContext.clear();
                sessionExecutionGuard.release(sessionId);
            }
        });
        return emitter;
    }

    private void streamRun(SseEmitter emitter, AiOpsRunResult runResult) throws IOException {
        String report = runResult.getMarkdownReport();
        for (int i = 0; i < report.length(); i += 50) {
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.content(report.substring(i, Math.min(report.length(), i + 50))), MediaType.APPLICATION_JSON));
        }
        emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
        emitter.complete();
    }

    private void sendRunFailure(SseEmitter emitter, Exception exception) {
        try {
            emitter.send(SseEmitter.event().name("message")
                    .data(SseMessage.error(toApiError(exception)), MediaType.APPLICATION_JSON));
        } catch (IOException sendException) {
            logger.warn("Unable to send run failure event", sendException);
        } finally {
            emitter.complete();
        }
    }

    private ResponseEntity<ApiResponse<AIOpsResponse>> runAiOpsSync(AiOpsRunContext context) {
        try {
            AiOpsRunResult runResult = runAiOps(context);
            return ResponseEntity.ok(ApiResponse.success(AIOpsResponse.from(runResult)));
        } catch (Exception exception) {
            logger.error("AI Ops 非流式执行失败, mode={}, caseId={}", context.getMode(), context.getCaseId(), exception);
            ErrorCode errorCode = exception instanceof IllegalArgumentException
                    || exception instanceof UnsupportedOperationException
                    ? ErrorCode.BUSINESS_ERROR : ErrorCode.MODEL_UNAVAILABLE;
            throw new BusinessException(errorCode,
                    "AI Ops 执行失败: " + safeMessage(exception));
        }
    }

    private SseEmitter startAiOpsStream(AiOpsRunContext context) {
        SseEmitter emitter = new SseEmitter(600000L);
        executor.execute(() -> {
            try {
                logger.info("收到 AI Ops 请求 - mode={}, caseId={}, runId={}",
                        context.getMode(), context.getCaseId(), context.getRunId());
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.content("正在读取告警并拆解任务...\n"), MediaType.APPLICATION_JSON));

                AiOpsRunResult runResult = runAiOps(context);
                String report = runResult.getMarkdownReport();
                for (int i = 0; i < report.length(); i += 50) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content(report.substring(i, Math.min(i + 50, report.length()))),
                                    MediaType.APPLICATION_JSON));
                }
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (Exception exception) {
                logger.error("AI Ops 协作失败, mode={}, caseId={}", context.getMode(), context.getCaseId(), exception);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error(toApiError(exception)), MediaType.APPLICATION_JSON));
                } catch (IOException sendException) {
                    logger.error("发送 AI Ops 错误消息失败", sendException);
                }
                emitter.complete();
            }
        });
        return emitter;
    }

    private AiOpsRunResult runAiOps(AiOpsRunContext context) throws Exception {
        String profileName = context.getMode() == AiOpsRunMode.REPLAY
                ? AgentProfileService.EVALUATION : AgentProfileService.AIOPS_LIVE;
        return runAiOps(context, agentProfileService.getActive(profileName));
    }

    private AiOpsRunResult runAiOps(AiOpsRunContext context, AgentProfileService.ProfileSnapshot profile)
            throws Exception {
        ChatModel chatModel = chatService.createChatModel(profile.sampling());
        return aiOpsRunService.run(chatModel, context, profile.prompts());
    }

    private String safeMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }


    /**
     * 获取会话信息
     */
    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

        Optional<ChatSessionService.SessionDetails> session = chatSessionService.findSession(sessionId);
        if (session.isEmpty()) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "会话不存在");
        }
        SessionInfoResponse response = new SessionInfoResponse();
        response.setSessionId(sessionId);
        response.setMessagePairCount(session.get().messagePairCount());
        response.setCreateTime(session.get().createTime());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/chat/session/{sessionId}/messages")
    public ResponseEntity<ApiResponse<List<SessionMessageResponse>>> getSessionMessages(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer beforeSequence) {
        List<SessionMessageResponse> messages = chatSessionService.loadMessages(sessionId).stream()
                .filter(message -> beforeSequence == null || message.getSequenceNo() < beforeSequence)
                .map(SessionMessageResponse::from)
                .toList();
        return ResponseEntity.ok(ApiResponse.success(messages));
    }

    private ApiError toApiError(Throwable error) {
        if (error instanceof AppException appException) {
            return ApiError.of(appException.getErrorCode(), appException.getMessage());
        }
        if (error instanceof UnsupportedOperationException) {
            return ApiError.of(ErrorCode.BUSINESS_ERROR, error.getMessage());
        }
        logger.error("SSE request failed", error);
        return ApiError.of(ErrorCode.MODEL_UNAVAILABLE);
    }

    private void sendSseError(SseEmitter emitter, Throwable error) throws IOException {
        emitter.send(SseEmitter.event().name("message")
                .data(SseMessage.error(toApiError(error)), MediaType.APPLICATION_JSON));
    }

    // ==================== 内部类 ====================

    /**
     * 聊天请求
     */
    @Setter
    @Getter
    public static class ChatRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
        
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Question")
        @com.fasterxml.jackson.annotation.JsonAlias({"question", "QUESTION"})
        private String Question;

    }

    @Setter
    @Getter
    public static class SessionAiOpsRequest {
        private String incidentPrompt;
        private Integer maxSteps = 20;
    }

    @Setter
    @Getter
    public static class EvaluationRunRequest {
        private String dataset = "cloud-ops-bench";
        private String caseId;
        private Integer maxSteps = 20;
        private String agentProfile = AgentProfileService.AIOPS_LIVE;
        private Integer profileVersion;
    }

    /**
     * 清空会话请求
     */
    @Setter
    @Getter
    public static class ClearRequest {
        @com.fasterxml.jackson.annotation.JsonProperty(value = "Id")
        @com.fasterxml.jackson.annotation.JsonAlias({"id", "ID"})
        private String Id;
    }

    // ==================== 内部类 ====================

    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

    @Setter
    @Getter
    public static class SessionMessageResponse {
        private String messageId;
        private String sessionId;
        private int sequenceNo;
        private String role;
        private String content;
        private String messageType;
        private String status;
        private String runId;
        private long createdAt;

        static SessionMessageResponse from(ChatMessageEntity message) {
            SessionMessageResponse response = new SessionMessageResponse();
            response.setMessageId(message.getMessageId());
            response.setSessionId(message.getSessionId());
            response.setSequenceNo(message.getSequenceNo() == null ? 0 : message.getSequenceNo());
            response.setRole(message.getRole());
            response.setContent(message.getContent());
            response.setMessageType(message.getMessageType() == null ? "CHAT" : message.getMessageType());
            response.setStatus(message.getStatus());
            response.setRunId(message.getRunId());
            response.setCreatedAt(message.getCreatedAt() == null ? 0L
                    : message.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli());
            return response;
        }
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

}
