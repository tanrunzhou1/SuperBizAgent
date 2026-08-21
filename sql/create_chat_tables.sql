-- 聊天会话与消息表。
-- 使用方式：在目标 MySQL 数据库中手工执行本文件。

-- 一条记录对应一个可恢复的聊天会话。
CREATE TABLE chat_session (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    session_id      CHAR(36)        NOT NULL COMMENT '会话唯一标识（UUID）',
    title           VARCHAR(255)    DEFAULT NULL COMMENT '会话标题',
    summary         MEDIUMTEXT      COMMENT '较早聊天消息的上下文摘要',
    summary_covered_sequence_no INT UNSIGNED NOT NULL DEFAULT 0 COMMENT '摘要已覆盖到的最后一条消息序号',
    last_message_at DATETIME(3)     DEFAULT NULL COMMENT '最后一条消息时间',
    created_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at      DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_session_session_id (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '聊天会话主表';

-- 一条记录对应会话内的一条用户消息、助手消息或系统消息。
CREATE TABLE chat_message (
    id                   BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    message_id           CHAR(36)        NOT NULL COMMENT '消息唯一标识（UUID）',
    session_id           CHAR(36)        NOT NULL COMMENT '所属会话标识',
    sequence_no          INT UNSIGNED    NOT NULL COMMENT '会话内消息顺序，从 1 开始',
    role                 VARCHAR(16)     NOT NULL COMMENT '角色：USER、ASSISTANT、SYSTEM、TOOL',
    content              MEDIUMTEXT      COMMENT '消息正文；敏感内容应先脱敏后保存',
    trace_id             VARCHAR(64)     DEFAULT NULL COMMENT '请求链路追踪标识，可关联工具调用审计表',
    created_at           DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_chat_message_message_id (message_id),
    UNIQUE KEY uk_chat_message_session_sequence (session_id, sequence_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '聊天消息明细表';
