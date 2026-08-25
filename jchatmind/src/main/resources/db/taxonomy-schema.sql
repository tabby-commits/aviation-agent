-- 第二步：分类体系 / 论文归属 / 参数证据（SPEC 数据模型第 1 节，按 2026-08-26 设计决策简化为 1 层 6 类）
CREATE TABLE IF NOT EXISTS taxonomy_node
(
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code        VARCHAR(64)  NOT NULL,
    parent_code VARCHAR(64),
    name_en     VARCHAR(256) NOT NULL,
    name_cn     VARCHAR(128) NOT NULL,
    description TEXT,
    level       INT          NOT NULL DEFAULT 1,
    created_at  TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_taxonomy_code UNIQUE (code)
);

CREATE TABLE IF NOT EXISTS paper_taxonomy
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    doc_id          VARCHAR(128) NOT NULL,
    taxonomy_code   VARCHAR(64)  NOT NULL,
    membership_type VARCHAR(64)  NOT NULL DEFAULT 'assigned',
    source          VARCHAR(64),
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    CONSTRAINT uk_paper_taxonomy UNIQUE (doc_id, taxonomy_code, membership_type)
);

CREATE INDEX IF NOT EXISTS idx_paper_taxonomy_code ON paper_taxonomy (taxonomy_code);

CREATE TABLE IF NOT EXISTS parameter_evidence
(
    id                        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    decision_id               VARCHAR(64) NOT NULL,
    doc_id                    VARCHAR(128) NOT NULL,
    technical_object          TEXT,
    parameter_name_raw        TEXT,
    parameter_name_canonical  VARCHAR(256),
    parameter_family          VARCHAR(256),
    value_raw                 TEXT,
    comparator                VARCHAR(16),
    value_min                 DOUBLE PRECISION,
    value_max                 DOUBLE PRECISION,
    unit_raw                  VARCHAR(128),
    unit_normalized           VARCHAR(128),
    condition_text            TEXT,
    evidence_text             TEXT,
    page_number               INT,
    section                   VARCHAR(256),
    source_type               VARCHAR(64),
    leaf_path                 TEXT,
    result_form               VARCHAR(64),
    review_status             VARCHAR(64),
    first_author_country      VARCHAR(64),
    title                     TEXT,
    publish_year              INT,
    created_at                TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_parameter_decision UNIQUE (decision_id)
);

CREATE INDEX IF NOT EXISTS idx_param_family ON parameter_evidence (parameter_family);
CREATE INDEX IF NOT EXISTS idx_param_country ON parameter_evidence (first_author_country);
CREATE INDEX IF NOT EXISTS idx_param_doc ON parameter_evidence (doc_id);

-- 分类体系种子（1 层 6 类，幂等插入）
-- 依据：完整稿件.md 4.1 节系统层次；name_en 与 EASC 一级类目 1:1 对应（论文归属导入按此映射）
INSERT INTO taxonomy_node (code, name_en, name_cn, description, level)
VALUES
    ('constellation-design', 'Constellation Design Technologies', '星座构型与轨道设计',
     '星座构型、轨道设计、覆盖与部署策略，对应星座网络空间段布局', 1),
    ('optical-isl', 'Inter-Satellite Optical Communication Technologies', '星间激光通信',
     '激光星间链路的信道、捕获跟瞄、调制编码与光传输', 1),
    ('inter-satellite-networking', 'Inter-Satellite Networking Technologies', '星间组网与网络协议',
     '星间链路组网、路由、切换、拥塞控制与传输协议', 1),
    ('space-computing', 'Space Computing Technologies', '空间计算与星载智能',
     '星载计算、在轨数据处理、联邦学习与任务卸载', 1),
    ('sat-ground-integration', 'Satellite-Terrestrial Integrated Networking Technologies', '星地融合网络',
     '星地一体化组网、地面系统、NTN 融合与用户接入', 1),
    ('interference-mitigation', 'Interference Mitigation Technologies', '干扰抑制',
     '频谱共存、干扰协调、抗干扰与资源规避', 1)
ON CONFLICT (code) DO NOTHING;
