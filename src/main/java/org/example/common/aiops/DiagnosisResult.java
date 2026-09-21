package org.example.common.aiops;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Agent 的结构化诊断事实。Markdown 报告和测评评分都基于此对象生成。
 */
@Data
public class DiagnosisResult {
    private String runId;
    private AiOpsRunMode mode;
    private String status = "UNCONFIRMED";
    private String faultObject;
    private String rootCause;
    private Double confidence;
    private String impact;
    private List<Evidence> keyEvidence = new ArrayList<>();
    private List<RecommendedAction> recommendedActions = new ArrayList<>();
    private String rawReport;
    private String parseError;

    @Data
    public static class Evidence {
        private String toolCallId;
        private String tool;
        private String finding;
    }

    @Data
    public static class RecommendedAction {
        private String priority;
        private String action;
        private String risk;
    }
}
