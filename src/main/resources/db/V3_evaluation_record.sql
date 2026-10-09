-- Persist single-case evaluation requests and outputs.
-- Execute manually after V1_init.sql and V2_unified_agent_workspace.sql.

CREATE TABLE IF NOT EXISTS evaluation_record (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    run_id                TEXT NOT NULL UNIQUE,
    type                  TEXT NOT NULL CHECK (type IN ('REPORT', 'SCORE')),
    request_body          TEXT NOT NULL,
    response_body         TEXT,
    dataset               TEXT NOT NULL,
    case_id               TEXT NOT NULL,
    status                TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED')),
    error_message         TEXT,
    agent_profile         TEXT NOT NULL,
    profile_version       INTEGER NOT NULL,
    model_provider        TEXT,
    model_name            TEXT,
    started_at            TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at          TEXT
);

CREATE INDEX IF NOT EXISTS idx_evaluation_record_case_started
    ON evaluation_record (dataset, case_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_evaluation_record_type_status_started
    ON evaluation_record (type, status, started_at DESC);

INSERT OR IGNORE INTO db_schema_version (version, description)
VALUES ('V3', 'persist evaluation requests and results');
