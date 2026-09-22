package org.example.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.example.common.aiops.AiOpsRunMode;
import org.example.common.aiops.AiOpsRunResult;
import org.example.common.aiops.AiOpsRunTrace;
import org.example.common.aiops.DiagnosisResult;

/**
 * AIOps 非流式响应。保留结构化诊断、Markdown 报告和工具轨迹，便于
 * 测评调用方直接保存或继续评分。
 */
@Getter
@AllArgsConstructor
public class AIOpsResponse {
    private String runId;
    private AiOpsRunMode mode;
    private String caseId;
    private DiagnosisResult diagnosis;
    private String markdownReport;
    private AiOpsRunTrace trace;

    public static AIOpsResponse from(AiOpsRunResult result) {
        return new AIOpsResponse(
                result.getContext().getRunId(),
                result.getContext().getMode(),
                result.getContext().getCaseId(),
                result.getDiagnosis(),
                result.getMarkdownReport(),
                result.getTrace());
    }
}
