package org.example.common.aiops;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 一次 AIOps 运行的统一结果。
 */
@Getter
@AllArgsConstructor
public class AiOpsRunResult {
    private final AiOpsRunContext context;
    private final DiagnosisResult diagnosis;
    private final String markdownReport;
    private final AiOpsRunTrace trace;
}
