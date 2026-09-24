-- Unified Agent profiles and AIOps messages.
-- Execute manually after V1_init.sql.

ALTER TABLE chat_message ADD COLUMN message_type TEXT NOT NULL DEFAULT 'CHAT';
ALTER TABLE chat_message ADD COLUMN status TEXT;
ALTER TABLE chat_message ADD COLUMN run_id TEXT;

CREATE INDEX IF NOT EXISTS idx_chat_message_run_id ON chat_message (run_id);
CREATE INDEX IF NOT EXISTS idx_chat_message_session_type_sequence
    ON chat_message (session_id, message_type, sequence_no);

CREATE TABLE IF NOT EXISTS agent_profile (
    profile       TEXT PRIMARY KEY,
    active_version INTEGER,
    updated_at    TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_profile_version (
    id                  TEXT PRIMARY KEY,
    profile             TEXT NOT NULL,
    version_no          INTEGER NOT NULL,
    prompt_config_json  TEXT NOT NULL,
    sampling_config_json TEXT NOT NULL,
    created_at          TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    applied_at          TEXT,
    CONSTRAINT uk_agent_profile_version UNIQUE (profile, version_no),
    CONSTRAINT fk_agent_profile_version_profile FOREIGN KEY (profile)
        REFERENCES agent_profile(profile)
);

CREATE INDEX IF NOT EXISTS idx_agent_profile_version_lookup
    ON agent_profile_version (profile, version_no DESC);

INSERT OR IGNORE INTO agent_profile (profile, active_version) VALUES ('CHAT', NULL);
INSERT OR IGNORE INTO agent_profile (profile, active_version) VALUES ('AIOPS_LIVE', NULL);
INSERT OR IGNORE INTO agent_profile (profile, active_version) VALUES ('EVALUATION', NULL);

INSERT OR IGNORE INTO db_schema_version (version, description)
VALUES ('V2', 'unified chat, AIOps, evaluation and agent profiles');
