package org.example.common.tool;

import org.example.common.api.ApiError;
import org.example.common.api.TraceIdContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

@Component
public class ToolExecutionTemplate {
    private static final Logger logger = LoggerFactory.getLogger(ToolExecutionTemplate.class);

    private final ToolRetryProperties retryProperties;

    public ToolExecutionTemplate(ToolRetryProperties retryProperties) {
        this.retryProperties = retryProperties;
    }

    public <T> ToolResult<T> execute(String toolName, ThrowingSupplier<T> action,
            Function<Exception, ApiError> errorMapper) {
        ToolRetryProperties.RetryPolicy policy = retryProperties.forTool(toolName);
        int maxAttempts = Math.max(1, policy.getMaxAttempts());

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                T data = action.get();
                logger.info("tool invocation succeeded, tool={}, traceId={}, attempts={}", toolName,
                        TraceIdContext.getOrCreate(), attempt);
                return ToolResult.success(data, "工具调用成功", attempt);
            } catch (Exception exception) {
                ApiError error = errorMapper.apply(exception);
                if (!error.isRetryable() || attempt == maxAttempts) {
                    logger.warn("tool invocation failed permanently, tool={}, traceId={}, attempts={}, errorCode={}",
                            toolName, error.getTraceId(), attempt, error.getCode(), exception);
                    return ToolResult.failure(error, attempt);
                }

                long delayMs = calculateDelay(policy, attempt);
                logger.warn("tool invocation failed, retrying, tool={}, traceId={}, attempt={}/{}, delayMs={}, errorCode={}",
                        toolName, error.getTraceId(), attempt, maxAttempts, delayMs, error.getCode(), exception);
                if (!sleep(delayMs)) {
                    return ToolResult.failure(error, attempt);
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
