package org.example.common.tool;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.common.api.ApiError;
import org.example.common.api.ChatSessionContext;
import org.example.common.api.TraceIdContext;
import org.example.entity.ToolInvocationAttemptEntity;
import org.example.entity.ToolInvocationEntity;
import org.example.mapper.ToolInvocationAttemptMapper;
import org.example.mapper.ToolInvocationMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 工具调用审计持久化服务。
 */
@Service
public class ToolInvocationAuditService {
    private static final int SUMMARY_MAX_LENGTH = 8_000;
    private static final String RUNNING_STATUS = "RUNNING";
    private static final String SUCCESS_STATUS = "SUCCESS";
    private static final String FAILED_STATUS = "FAILED";

    private final ToolInvocationMapper toolInvocationMapper;
    private final ToolInvocationAttemptMapper toolInvocationAttemptMapper;
    private final ObjectMapper objectMapper;

    public ToolInvocationAuditService(ToolInvocationMapper toolInvocationMapper,
            ToolInvocationAttemptMapper toolInvocationAttemptMapper, ObjectMapper objectMapper) {
        this.toolInvocationMapper = toolInvocationMapper;
        this.toolInvocationAttemptMapper = toolInvocationAttemptMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 创建工具逻辑调用审计记录。
     *
     * @param toolName 工具名称
     * @param descriptor 工具描述
     * @param requestSummary 请求摘要
     * @return 工具调用标识
     */
    public String start(String toolName, ToolRegistry.ToolDescriptor descriptor, String requestSummary) {
        String invocationId = UUID.randomUUID().toString();
        ToolInvocationEntity invocation = new ToolInvocationEntity();
        invocation.setInvocationId(invocationId);
        invocation.setTraceId(TraceIdContext.getOrCreate());
        invocation.setSessionId(ChatSessionContext.get());
        invocation.setToolName(toolName);
        invocation.setToolSource(descriptor.getSource().name());
        invocation.setStatus(RUNNING_STATUS);
        invocation.setStartedAt(LocalDateTime.now());
        invocation.setRequestSummary(truncate(requestSummary));
        assertAffectedRows(toolInvocationMapper.insert(invocation), "创建工具调用审计记录失败");
        return invocationId;
    }

    /**
     * 创建一次工具执行尝试记录。
     *
     * @param invocationId 工具调用标识
     * @param attempt 尝试序号
     * @param requestSummary 请求摘要
     * @return 尝试序号
     */
    public long startAttempt(String invocationId, int attempt, String requestSummary) {
        ToolInvocationAttemptEntity invocationAttempt = new ToolInvocationAttemptEntity();
        invocationAttempt.setInvocationId(invocationId);
        invocationAttempt.setAttemptNo(attempt);
        invocationAttempt.setStatus(RUNNING_STATUS);
        invocationAttempt.setStartedAt(LocalDateTime.now());
        invocationAttempt.setRequestSummary(truncate(requestSummary));
        assertAffectedRows(toolInvocationAttemptMapper.insert(invocationAttempt), "创建工具尝试审计记录失败");
        return attempt;
    }

    /**
     * 标记一次工具尝试成功。
     *
     * @param invocationId 工具调用标识
     * @param attempt 尝试序号
     * @param result 工具返回结果
     */
    public void succeedAttempt(String invocationId, long attempt, Object result) {
        assertAffectedRows(toolInvocationAttemptMapper.update(null,
                new LambdaUpdateWrapper<ToolInvocationAttemptEntity>()
                        .eq(ToolInvocationAttemptEntity::getInvocationId, invocationId)
                        .eq(ToolInvocationAttemptEntity::getAttemptNo, attempt)
                        .set(ToolInvocationAttemptEntity::getStatus, SUCCESS_STATUS)
                        .set(ToolInvocationAttemptEntity::getFinishedAt, LocalDateTime.now())
                        .set(ToolInvocationAttemptEntity::getResponseSummary, toSummary(result))), "更新工具尝试成功状态失败");
    }

    /**
     * 标记一次工具尝试失败。
     *
     * @param invocationId 工具调用标识
     * @param attempt 尝试序号
     * @param error 工具错误信息
     */
    public void failAttempt(String invocationId, long attempt, ApiError error) {
        assertAffectedRows(toolInvocationAttemptMapper.update(null,
                new LambdaUpdateWrapper<ToolInvocationAttemptEntity>()
                        .eq(ToolInvocationAttemptEntity::getInvocationId, invocationId)
                        .eq(ToolInvocationAttemptEntity::getAttemptNo, attempt)
                        .set(ToolInvocationAttemptEntity::getStatus, FAILED_STATUS)
                        .set(ToolInvocationAttemptEntity::getFinishedAt, LocalDateTime.now())
                        .set(ToolInvocationAttemptEntity::getErrorCode, error.getCode())
                        .set(ToolInvocationAttemptEntity::getErrorMessage, truncate(error.getMessage()))
                        .set(ToolInvocationAttemptEntity::getRetryable, error.isRetryable())), "更新工具尝试失败状态失败");
    }

    /**
     * 标记工具逻辑调用结束。
     *
     * @param invocationId 工具调用标识
     * @param result 工具执行结果
     */
    public void complete(String invocationId, ToolResult<?> result) {
        ApiError error = result.getError();
        assertAffectedRows(toolInvocationMapper.update(null, new LambdaUpdateWrapper<ToolInvocationEntity>()
                .eq(ToolInvocationEntity::getInvocationId, invocationId)
                .set(ToolInvocationEntity::getStatus, result.isSuccess() ? SUCCESS_STATUS : FAILED_STATUS)
                .set(ToolInvocationEntity::getFinishedAt, LocalDateTime.now())
                .set(ToolInvocationEntity::getAttemptCount, result.getAttempts())
                .set(ToolInvocationEntity::getErrorCode, error == null ? null : error.getCode())
                .set(ToolInvocationEntity::getErrorMessage, error == null ? null : truncate(error.getMessage()))
                .set(ToolInvocationEntity::getResultSummary, result.isSuccess() ? toSummary(result.getData()) : null)),
                "更新工具调用结束状态失败");
    }

    private void assertAffectedRows(int affectedRows, String errorMessage) {
        if (affectedRows != 1) {
            throw new IllegalStateException(errorMessage);
        }
    }

    private String toSummary(Object value) {
        try {
            return truncate(objectMapper.writeValueAsString(value));
        } catch (JsonProcessingException exception) {
            return truncate(String.valueOf(value));
        }
    }

    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, SUMMARY_MAX_LENGTH) + "...";
    }
}
