package org.example.dto;

import org.example.common.aiops.AiOpsRunContext;
import org.example.common.aiops.AiOpsRunMode;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AIOps 请求 DTO
 */
@Data
public class AIOpsRequest {
    
    /**
     * 用户请求描述
     */
    private String userRequest;

    /** 运行模式；不传时默认为真实运行模式。 */
    private AiOpsRunMode mode = AiOpsRunMode.LIVE;

    /** Cloud-OpsBench 测评案例标识，REPLAY 模式使用。 */
    private String caseId;

    /** 故障描述或告警上下文。 */
    private String incidentPrompt;

    /** 单次运行最大 Agent 步数。 */
    private Integer maxSteps = 20;

    /** 是否使用 SSE 流式响应；默认 true，保持现有接口行为兼容。 */
    private Boolean stream = true;

    private List<String> serviceScope = new ArrayList<>();
    private String startTime;
    private String endTime;

    public boolean shouldStream() {
        return stream == null || stream;
    }

    public AiOpsRunContext toRunContext() {
        AiOpsRunContext context = new AiOpsRunContext();
        context.setMode(mode == null ? AiOpsRunMode.LIVE : mode);
        context.setCaseId(caseId);
        context.setIncidentPrompt(incidentPrompt == null || incidentPrompt.isBlank()
                ? userRequest : incidentPrompt);
        context.setMaxSteps(maxSteps == null || maxSteps < 1 ? 20 : Math.min(maxSteps, 100));
        context.setServiceScope(serviceScope == null ? new ArrayList<>() : new ArrayList<>(serviceScope));
        context.setStartTime(startTime);
        context.setEndTime(endTime);
        return context;
    }
}
