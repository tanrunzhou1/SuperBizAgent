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
    /**
     * REPLAY 模式下由案例 metadata 注入，LIVE 模式不参与工具参数构造。
     */
    private String namespace;
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
        String replayHint = mode == AiOpsRunMode.REPLAY
                ? "\n当前是 Cloud-OpsBench 回放，只能使用案例回放工具。案例 ID=" + caseId
                    + "，namespace=" + (namespace == null ? "(由工具参数校验)" : namespace)
                    + "。必须使用案例中的精确 namespace 和服务名，禁止使用 default、example-service 等猜测值。"
                : "";
        return "你是企业级 SRE。请针对以下事件执行规划、证据查询、校验和总结：\n" + prompt
                + replayHint
                + "\n不得编造工具未返回的数据；工具返回 success=false 时不是证据，禁止将其写入 keyEvidence；证据不足时必须明确说明。";
    }
}
