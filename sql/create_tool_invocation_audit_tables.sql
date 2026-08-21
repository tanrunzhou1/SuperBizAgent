-- 工具调用审计表。
-- 使用方式：在目标 MySQL 数据库中手工执行本文件。
-- 不记录完整原始参数和响应；仅保存脱敏、截断后的摘要，避免敏感数据进入审计库。

-- 一条记录对应 Agent 发起的一次逻辑工具调用。
CREATE TABLE tool_invocation (
    id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    invocation_id       CHAR(36)        NOT NULL COMMENT '逻辑调用唯一标识（UUID）',
    trace_id            VARCHAR(64)     NOT NULL COMMENT '请求链路追踪标识',
    session_id          VARCHAR(128)    DEFAULT NULL COMMENT '会话标识',
    agent_name          VARCHAR(64)     DEFAULT NULL COMMENT '发起调用的 Agent 名称',
    tool_name           VARCHAR(128)    NOT NULL COMMENT '实际 @Tool 名称或 MCP 工具名称',
    tool_source         VARCHAR(16)     NOT NULL COMMENT '工具来源：LOCAL、MCP',
    status              VARCHAR(16)     NOT NULL COMMENT '调用状态：RUNNING、SUCCESS、FAILED',
    started_at          DATETIME(3)     NOT NULL COMMENT '逻辑调用开始时间',
    finished_at         DATETIME(3)     DEFAULT NULL COMMENT '逻辑调用完成时间',
    attempt_count       INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '实际执行次数，包含重试',
    error_code          INT             DEFAULT NULL COMMENT '最终错误码',
    error_message       VARCHAR(1024)   DEFAULT NULL COMMENT '最终错误信息（脱敏、截断）',
    request_summary     TEXT            COMMENT '请求参数摘要（脱敏、截断）',
    result_summary      TEXT            COMMENT '结果摘要（脱敏、截断）',
    created_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    updated_at          DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_invocation_invocation_id (invocation_id),
    KEY idx_tool_invocation_trace_id (trace_id),
    KEY idx_tool_invocation_session_id (session_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 工具调用审计主表';

-- 一条记录对应一次真实执行，包含首次执行和每次代码级重试。
CREATE TABLE tool_invocation_attempt (
    id               BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    invocation_id    CHAR(36)        NOT NULL COMMENT '关联 tool_invocation.invocation_id',
    attempt_no       INT UNSIGNED    NOT NULL COMMENT '第几次尝试，从 1 开始',
    status           VARCHAR(16)     NOT NULL COMMENT '尝试状态：SUCCESS、FAILED',
    started_at       DATETIME(3)     NOT NULL COMMENT '本次尝试开始时间',
    finished_at      DATETIME(3)     DEFAULT NULL COMMENT '本次尝试完成时间',
    error_code       INT             DEFAULT NULL COMMENT '本次尝试错误码',
    error_message    VARCHAR(1024)   DEFAULT NULL COMMENT '本次尝试错误信息（脱敏、截断）',
    retryable        TINYINT(1)      NOT NULL DEFAULT 0 COMMENT '本次失败是否可重试：0 否，1 是',
    request_summary  TEXT            COMMENT '本次请求参数摘要（脱敏、截断）',
    response_summary TEXT            COMMENT '本次响应摘要（脱敏、截断）',
    created_at       DATETIME(3)     NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_invocation_attempt (invocation_id, attempt_no)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 工具调用执行尝试明细表';
