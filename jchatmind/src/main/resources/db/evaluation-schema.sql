-- 第六步：智能体运行记录与客观检查（SPEC 第 5 节，中期报告 3.4 节评价方法）
CREATE TABLE IF NOT EXISTS evaluation_run
(
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    chat_session_id VARCHAR(128),
    question        TEXT NOT NULL,
    report          TEXT,
    agent_config    JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_eval_run_session ON evaluation_run (chat_session_id);

CREATE TABLE IF NOT EXISTS evaluation_check_item
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id     UUID NOT NULL,
    item_key   VARCHAR(64) NOT NULL,
    status     VARCHAR(32) NOT NULL DEFAULT 'pending',
    note       TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now(),
    CONSTRAINT uk_eval_check UNIQUE (run_id, item_key)
);

-- 八项客观检查清单（中期报告 3.4 节）在服务层固定生成
