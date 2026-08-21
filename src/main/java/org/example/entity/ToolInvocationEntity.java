package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计主记录持久化实体。
 */
@Data
@TableName("tool_invocation")
public class ToolInvocationEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String invocationId;
    private String traceId;
    private String sessionId;
    private String agentName;
    private String toolName;
    private String toolSource;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer attemptCount;
    private Integer errorCode;
    private String errorMessage;
    private String requestSummary;
    private String resultSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
