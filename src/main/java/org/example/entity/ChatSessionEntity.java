package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话持久化实体。
 */
@Data
@TableName("chat_session")
public class ChatSessionEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String sessionId;
    private String title;
    private String summary;
    private Integer summaryCoveredSequenceNo;
    private LocalDateTime lastMessageAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
