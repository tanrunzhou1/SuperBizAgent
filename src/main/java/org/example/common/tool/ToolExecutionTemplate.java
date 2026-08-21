package org.example.common.tool;

import org.example.common.api.ApiError;
import org.example.common.api.TraceIdContext;
import org.example.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@Component
public class ToolExecutionTemplate {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionTemplate.class);

    private final ToolRegistry toolRegistry;
    private final ToolInvocationAuditService toolInvocationAuditService;

    public ToolExecutionTemplate(ToolRegistry toolRegistry, ToolInvocationAuditService toolInvocationAuditService) {
        this.toolRegistry = toolRegistry;
        this.toolInvocationAuditService = toolInvocationAuditService;
    }

    public <T> ToolResult<T> execute(String toolName, ThrowingSupplier<T> action,
            Function<Exception, ApiError> errorMapper) {
        return execute(toolName, null, action, errorMapper);
    }

    public <T> ToolResult<T> execute(String toolName, String requestSummary, ThrowingSupplier<T> action,
            Function<Exception, ApiError> errorMapper) {
        ToolRegistry.ToolDescriptor descriptor = toolRegistry.find(toolName).orElse(null);
        if (descriptor == null) {
            return ToolResult.failure(ApiError.of(ErrorCode.BUSINESS_ERROR, "工具未注册: " + toolName), 0);
        }
        if (!descriptor.isEnabled()) {
            return ToolResult.failure(ApiError.of(ErrorCode.BUSINESS_ERROR, "工具未启用: " + toolName), 0);
        }
        ToolRetryProperties.RetryPolicy policy = descriptor.getRetryPolicy();
        int maxAttempts = Math.max(1, policy.getMaxAttempts());
        String invocationId = toolInvocationAuditService.start(toolName, descriptor, requestSummary);

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long attemptId = toolInvocationAuditService.startAttempt(invocationId, attempt, requestSummary);
            try {
                T data = action.get();
                logger.info("tool invocation succeeded, tool={}, traceId={}, attempts={}", toolName,
                        TraceIdContext.getOrCreate(), attempt);
                ToolResult<T> result = ToolResult.success(data, "工具调用成功", attempt);
                toolInvocationAuditService.succeedAttempt(invocationId, attemptId, data);
                toolInvocationAuditService.complete(invocationId, result);
                return result;
            } catch (Exception exception) {
                ApiError error = errorMapper.apply(exception);
                toolInvocationAuditService.failAttempt(invocationId, attemptId, error);
                if (!error.isRetryable() || attempt == maxAttempts) {
                    logger.warn("tool invocation failed permanently, tool={}, traceId={}, attempts={}, errorCode={}",
                            toolName, error.getTraceId(), attempt, error.getCode(), exception);
                    ToolResult<T> result = ToolResult.failure(error, attempt);
                    toolInvocationAuditService.complete(invocationId, result);
                    return result;
                }

                long delayMs = calculateDelay(policy, attempt);
                logger.warn("tool invocation failed, retrying, tool={}, traceId={}, attempt={}/{}, delayMs={}, errorCode={}",
                        toolName, error.getTraceId(), attempt, maxAttempts, delayMs, error.getCode(), exception);
                if (!sleep(delayMs)) {
                    ToolResult<T> result = ToolResult.failure(error, attempt);
                    toolInvocationAuditService.complete(invocationId, result);
                    return result;
                }
            }
        }
        throw new IllegalStateException("unreachable tool retry state");
    }

    private long calculateDelay(ToolRetryProperties.RetryPolicy policy, int failedAttempt) {
        double base = policy.getInitialIntervalMs() * Math.pow(policy.getMultiplier(), failedAttempt - 1);
        long cappedDelay = Math.min(Math.round(base), policy.getMaxIntervalMs());
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, cappedDelay / 5 + 1));
        return cappedDelay + jitter;
    }

    private boolean sleep(long delayMs) {
        try {
            Thread.sleep(delayMs);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
