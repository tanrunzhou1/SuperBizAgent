package org.example.common.aiops;

import org.springframework.stereotype.Component;

/**
 * 将结构化诊断结果渲染为面向运维人员的 Markdown 报告。
 */
@Component
public class MarkdownReportRenderer {

    public String render(DiagnosisResult diagnosis) {
        StringBuilder report = new StringBuilder();
        report.append("# 告警分析报告\n\n");
        report.append("## 运行信息\n\n");
        report.append("- 运行 ID：").append(value(diagnosis.getRunId())).append("\n");
        report.append("- 诊断状态：").append(value(diagnosis.getStatus())).append("\n");
        if (diagnosis.getConfidence() != null) {
            report.append("- 置信度：").append(diagnosis.getConfidence()).append("\n");
        }

        report.append("\n## 根因结论\n\n");
        report.append("- 受影响对象：").append(value(diagnosis.getFaultObject())).append("\n");
        report.append("- 根因：").append(value(diagnosis.getRootCause())).append("\n");
        if (diagnosis.getImpact() != null && !diagnosis.getImpact().isBlank()) {
            report.append("- 影响：").append(diagnosis.getImpact()).append("\n");
        }

        report.append("\n## 关键证据\n\n");
        if (diagnosis.getKeyEvidence().isEmpty()) {
            report.append("暂无结构化证据。\n");
        } else {
            for (DiagnosisResult.Evidence evidence : diagnosis.getKeyEvidence()) {
                report.append("- ")
                        .append(value(evidence.getTool()))
                        .append("：")
                        .append(value(evidence.getFinding()));
                if (evidence.getToolCallId() != null && !evidence.getToolCallId().isBlank()) {
                    report.append("（调用 ").append(evidence.getToolCallId()).append("）");
                }
                report.append("\n");
            }
        }

        report.append("\n## 处理建议\n\n");
        if (diagnosis.getRecommendedActions().isEmpty()) {
            report.append("暂无结构化处理建议，请人工复核。\n");
        } else {
            int index = 1;
            for (DiagnosisResult.RecommendedAction action : diagnosis.getRecommendedActions()) {
                report.append(index++).append(". ");
                if (action.getPriority() != null && !action.getPriority().isBlank()) {
                    report.append("[").append(action.getPriority()).append("] ");
                }
                report.append(value(action.getAction()));
                if (action.getRisk() != null && !action.getRisk().isBlank()) {
                    report.append("（风险：").append(action.getRisk()).append("）");
                }
                report.append("\n");
            }
        }

        if (diagnosis.getParseError() != null) {
            report.append("\n## 解析提示\n\n");
            report.append("Agent 未返回符合结构化协议的结果，以下为原始分析内容，需人工复核：\n\n");
            report.append(value(diagnosis.getRawReport())).append("\n");
        }
        return report.toString();
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }
}
