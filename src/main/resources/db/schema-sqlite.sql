-- SQLite schema for chat history and tool invocation audit records.
-- The script is idempotent and is executed during application startup.

CREATE TABLE IF NOT EXISTS chat_session (
    id                           INTEGER PRIMARY KEY AUTOINCREMENT,
    session_id                   TEXT NOT NULL,
    title                        TEXT,
    summary                      TEXT,
    summary_covered_sequence_no INTEGER NOT NULL DEFAULT 0,
    last_message_at              TEXT,
    created_at                   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chat_session_session_id UNIQUE (session_id)
);

CREATE TABLE IF NOT EXISTS chat_message (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    message_id   TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    sequence_no  INTEGER NOT NULL,
    role         TEXT NOT NULL,
    content      TEXT,
    trace_id     TEXT,
    created_at   TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_chat_message_message_id UNIQUE (message_id),
    CONSTRAINT uk_chat_message_session_sequence UNIQUE (session_id, sequence_no)
);

CREATE INDEX IF NOT EXISTS idx_chat_message_session_id
    ON chat_message (session_id);

CREATE TABLE IF NOT EXISTS tool_invocation (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    invocation_id    TEXT NOT NULL,
    trace_id         TEXT NOT NULL,
    session_id       TEXT,
    agent_name       TEXT,
    tool_name        TEXT NOT NULL,
    tool_source      TEXT NOT NULL,
    status           TEXT NOT NULL,
    started_at       TEXT NOT NULL,
    finished_at      TEXT,
    attempt_count    INTEGER NOT NULL DEFAULT 0,
    error_code       INTEGER,
    error_message    TEXT,
    request_summary  TEXT,
    result_summary   TEXT,
    created_at       TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tool_invocation_invocation_id UNIQUE (invocation_id)
);

CREATE INDEX IF NOT EXISTS idx_tool_invocation_trace_id
    ON tool_invocation (trace_id);

CREATE INDEX IF NOT EXISTS idx_tool_invocation_session_id
    ON tool_invocation (session_id);

CREATE TABLE IF NOT EXISTS tool_invocation_attempt (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    invocation_id     TEXT NOT NULL,
    attempt_no        INTEGER NOT NULL,
    status            TEXT NOT NULL,
    started_at        TEXT NOT NULL,
    finished_at       TEXT,
    error_code        INTEGER,
    error_message     TEXT,
    retryable         INTEGER NOT NULL DEFAULT 0,
    request_summary   TEXT,
    response_summary  TEXT,
    created_at        TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_tool_invocation_attempt UNIQUE (invocation_id, attempt_no)
);

CREATE INDEX IF NOT EXISTS idx_tool_invocation_attempt_invocation_id
    ON tool_invocation_attempt (invocation_id);
