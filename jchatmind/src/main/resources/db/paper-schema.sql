-- 论文元数据表（含筛选结论，SPEC 数据模型第 1 节）
-- doc_id: WoS 为 UT（WOS:xxxx），CNKI 为构造 ID（CNKI:md5hex）
-- screening_status: pending（未筛选）/ included（有效）/ excluded（排除）
CREATE TABLE IF NOT EXISTS paper
(
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id                    VARCHAR(128) NOT NULL,
    source_db                 VARCHAR(16)  NOT NULL,
    title                     TEXT         NOT NULL,
    abstract_text             TEXT,
    authors                   JSONB,
    affiliations              JSONB,
    first_author              VARCHAR(512),
    first_author_affiliation  TEXT,
    first_author_country      VARCHAR(64),
    country_evidence          TEXT,
    country_confidence        VARCHAR(32),
    publish_year              INT,
    journal                   VARCHAR(512),
    doc_type                  VARCHAR(64),
    cited_count               INT,
    doi                       VARCHAR(256),
    keywords                  TEXT,
    screening_status          VARCHAR(32) NOT NULL DEFAULT 'pending',
    exclude_reason            TEXT,
    file_name                 VARCHAR(512),
    created_at                TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at                TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_paper_doc_id UNIQUE (doc_id)
);

CREATE INDEX IF NOT EXISTS idx_paper_year ON paper (publish_year);
CREATE INDEX IF NOT EXISTS idx_paper_country ON paper (first_author_country);
CREATE INDEX IF NOT EXISTS idx_paper_screening ON paper (screening_status);
