package org.example.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息持久化实体。
 */
@Data
@TableName("chat_message")
public class ChatMessageEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String messageId;
    private String sessionId;
    private Integer sequenceNo;
    private String role;
    private String content;
    private String traceId;
    private LocalDateTime createdAt;
}
