package org.example.common.tool;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tool.retry")
public class ToolRetryProperties {
    private RetryPolicy defaults = new RetryPolicy();
    private RetryPolicy prometheus = new RetryPolicy();
    private RetryPolicy milvus = new RetryPolicy();

    public RetryPolicy forTool(String toolName) {
        RetryPolicy policy = switch (toolName) {
            case "prometheus" -> prometheus;
            case "milvus" -> milvus;
            default -> defaults;
        };
        return policy.resolve(defaults);
    }

    @Getter
    @Setter
    public static class RetryPolicy {
        private Integer maxAttempts;
        private Long initialIntervalMs;
        private Double multiplier;
        private Long maxIntervalMs;

        private RetryPolicy resolve(RetryPolicy fallback) {
            RetryPolicy resolved = new RetryPolicy();
            resolved.setMaxAttempts(maxAttempts != null ? maxAttempts : fallback.getMaxAttempts());
            resolved.setInitialIntervalMs(initialIntervalMs != null ? initialIntervalMs : fallback.getInitialIntervalMs());
            resolved.setMultiplier(multiplier != null ? multiplier : fallback.getMultiplier());
            resolved.setMaxIntervalMs(maxIntervalMs != null ? maxIntervalMs : fallback.getMaxIntervalMs());
            return resolved;
        }
    }
}
