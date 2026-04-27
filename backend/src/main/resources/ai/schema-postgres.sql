CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE TABLE IF NOT EXISTS ai_kb_version (
    id BIGSERIAL PRIMARY KEY,
    version_key VARCHAR(64) NOT NULL UNIQUE,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    description TEXT,
    document_count INTEGER NOT NULL DEFAULT 0,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT FALSE,
    created_by VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    activated_at TIMESTAMPTZ
);

CREATE TABLE IF NOT EXISTS ai_kb_document (
    id BIGSERIAL PRIMARY KEY,
    document_uid VARCHAR(80) NOT NULL UNIQUE,
    domain VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_ref VARCHAR(255),
    review_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    parse_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    summary TEXT,
    chunk_count INTEGER NOT NULL DEFAULT 0,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_kb_chunk (
    id BIGSERIAL PRIMARY KEY,
    chunk_uid VARCHAR(80) NOT NULL UNIQUE,
    document_uid VARCHAR(80) NOT NULL REFERENCES ai_kb_document(document_uid) ON DELETE CASCADE,
    version_key VARCHAR(64),
    domain VARCHAR(32) NOT NULL,
    title VARCHAR(255) NOT NULL,
    section_path TEXT,
    subject_key VARCHAR(120),
    content TEXT NOT NULL,
    ordinal INTEGER NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    search_vector tsvector,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_ai_kb_chunk_subject ON ai_kb_chunk(subject_key);
CREATE INDEX IF NOT EXISTS idx_ai_kb_chunk_domain ON ai_kb_chunk(domain);
CREATE INDEX IF NOT EXISTS idx_ai_kb_chunk_search_vector ON ai_kb_chunk USING GIN(search_vector);
CREATE INDEX IF NOT EXISTS idx_ai_kb_chunk_content_trgm ON ai_kb_chunk USING GIN(content gin_trgm_ops);

CREATE TABLE IF NOT EXISTS ai_kb_alias (
    id BIGSERIAL PRIMARY KEY,
    alias VARCHAR(120) NOT NULL,
    subject_key VARCHAR(120) NOT NULL,
    domain VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(alias, subject_key, domain)
);

CREATE TABLE IF NOT EXISTS ai_runtime_config (
    config_key VARCHAR(80) PRIMARY KEY,
    config_value TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(1536)
);

CREATE INDEX IF NOT EXISTS idx_vector_store_embedding ON vector_store USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_vector_store_metadata ON vector_store USING GIN(metadata);

CREATE TABLE IF NOT EXISTS ai_qa_session (
    session_id VARCHAR(80) PRIMARY KEY,
    openid VARCHAR(120),
    title VARCHAR(255),
    confirmed_entity VARCHAR(120),
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    update_time TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_qa_message (
    id BIGSERIAL PRIMARY KEY,
    message_id VARCHAR(80) NOT NULL UNIQUE,
    session_id VARCHAR(80) NOT NULL REFERENCES ai_qa_session(session_id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    answer_type VARCHAR(40),
    trace_id VARCHAR(80),
    sources JSONB,
    create_time TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_qa_log (
    id BIGSERIAL PRIMARY KEY,
    trace_id VARCHAR(80) NOT NULL UNIQUE,
    session_id VARCHAR(80),
    question TEXT NOT NULL,
    answer TEXT,
    answer_type VARCHAR(40),
    subject_key VARCHAR(120),
    retrieval_mode VARCHAR(40),
    hit_count INTEGER NOT NULL DEFAULT 0,
    web_used BOOLEAN NOT NULL DEFAULT FALSE,
    failure_reason VARCHAR(40),
    latency_ms BIGINT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_eval_case (
    id BIGSERIAL PRIMARY KEY,
    question TEXT NOT NULL,
    expected_subject VARCHAR(120),
    expected_keywords TEXT,
    category VARCHAR(60),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_eval_run (
    id BIGSERIAL PRIMARY KEY,
    run_key VARCHAR(80) NOT NULL UNIQUE,
    total_count INTEGER NOT NULL DEFAULT 0,
    pass_count INTEGER NOT NULL DEFAULT 0,
    report JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS ai_news_staging (
    id BIGSERIAL PRIMARY KEY,
    query TEXT NOT NULL,
    title TEXT,
    url TEXT,
    snippet TEXT,
    source VARCHAR(120),
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
