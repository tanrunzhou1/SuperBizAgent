package org.example.common.aiops;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 单次 AIOps 运行的有序工具调用轨迹。
 */
@Data
public class AiOpsRunTrace {
    private String runId;
    private Instant startedAt = Instant.now();
    private Instant finishedAt;
    private List<ToolCall> toolCalls = new ArrayList<>();

    public AiOpsRunTrace(String runId) {
        this.runId = runId == null || runId.isBlank() ? UUID.randomUUID().toString() : runId;
    }

    @Data
    public static class ToolCall {
        private String toolCallId;
        private String toolName;
        private String input;
        private String output;
        private boolean success;
        private long durationMs;
        private String error;
    }
}
