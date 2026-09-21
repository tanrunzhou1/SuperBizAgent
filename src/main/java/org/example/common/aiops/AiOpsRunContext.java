package org.example.common.aiops;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 一次 AIOps 运行的统一输入上下文。
 */
@Data
public class AiOpsRunContext {
    private String runId = UUID.randomUUID().toString();
    private AiOpsRunMode mode = AiOpsRunMode.LIVE;
    private String incidentPrompt;
    private String caseId;
    private int maxSteps = 20;
    private List<String> serviceScope = new ArrayList<>();
    private String startTime;
    private String endTime;

    public static AiOpsRunContext live(String incidentPrompt) {
        AiOpsRunContext context = new AiOpsRunContext();
        context.setMode(AiOpsRunMode.LIVE);
        context.setIncidentPrompt(incidentPrompt);
        return context;
    }

    public String taskPrompt() {
        String prompt = incidentPrompt == null || incidentPrompt.isBlank()
                ? "请读取当前活跃告警并执行故障分析。"
                : incidentPrompt.trim();
        return "你是企业级 SRE。请针对以下事件执行规划、证据查询、校验和总结：\n" + prompt
                + "\n不得编造工具未返回的数据；证据不足时必须明确说明。";
    }
}
