package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用单次尝试持久化实体。
 */
@Data
@TableName("tool_invocation_attempt")
public class ToolInvocationAttemptEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String invocationId;
    private Integer attemptNo;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
    private Integer errorCode;
    private String errorMessage;
    private Boolean retryable;
    private String requestSummary;
    private String responseSummary;
    private LocalDateTime createdAt;
}
