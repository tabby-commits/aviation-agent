CREATE TABLE IF NOT EXISTS analysis_run (
    id UUID PRIMARY KEY,
    session_id VARCHAR(128) NOT NULL,
    topic VARCHAR(64) NOT NULL,
    snapshot_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    stages JSONB NOT NULL DEFAULT '[]'::jsonb,
    skill_hashes JSONB NOT NULL DEFAULT '{}'::jsonb,
    error_text TEXT
);
CREATE INDEX IF NOT EXISTS idx_analysis_run_session ON analysis_run(session_id, started_at DESC);

CREATE TABLE IF NOT EXISTS report_version (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES analysis_run(id),
    session_id VARCHAR(128) NOT NULL,
    topic VARCHAR(64) NOT NULL,
    version INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    body JSONB NOT NULL,
    UNIQUE(session_id, topic, version)
);
CREATE INDEX IF NOT EXISTS idx_report_version_session ON report_version(session_id, topic, version DESC);

CREATE TABLE IF NOT EXISTS evidence_source (
    id VARCHAR(256) PRIMARY KEY,
    snapshot_id VARCHAR(128) NOT NULL,
    body JSONB NOT NULL
);
