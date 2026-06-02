CREATE TABLE IF NOT EXISTS report_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(255) NOT NULL,
    institution VARCHAR(255) NULL,
    publish_date DATE NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS report_document_author (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    author_name VARCHAR(128) NOT NULL,
    normalized_author_name VARCHAR(128) NOT NULL,
    author_order INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_document_author_report_id FOREIGN KEY (report_id) REFERENCES report_document(id),
    UNIQUE KEY uk_report_document_author_report_name (report_id, normalized_author_name),
    KEY idx_report_document_author_report_id (report_id),
    KEY idx_report_document_author_normalized (normalized_author_name)
);

CREATE TABLE IF NOT EXISTS report_chunk (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_uid VARCHAR(64) NOT NULL,
    parent_chunk_uid VARCHAR(64) NULL,
    chunk_type VARCHAR(16) NOT NULL DEFAULT 'CHILD',
    section_path VARCHAR(512) NULL,
    chunk_text TEXT NOT NULL,
    token_count INT NULL,
    page_number INT NULL,
    start_paragraph_id INT NULL,
    end_paragraph_id INT NULL,
    start_page_number INT NULL,
    end_page_number INT NULL,
    filter_reason VARCHAR(255) NULL,
    diagnostics TEXT NULL,
    vector_stored BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_chunk_report_id FOREIGN KEY (report_id) REFERENCES report_document(id),
    UNIQUE KEY uk_report_chunk_uid (chunk_uid),
    KEY idx_report_chunk_report_id (report_id),
    KEY idx_report_chunk_parent_uid (parent_chunk_uid),
    KEY idx_report_chunk_type (chunk_type),
    KEY idx_report_chunk_page_range (start_page_number, end_page_number),
    KEY idx_report_chunk_created_at (created_at)
);

CREATE TABLE IF NOT EXISTS report_ocr_page (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    page_number INT NOT NULL,
    raw_text MEDIUMTEXT NULL,
    cleaned_text MEDIUMTEXT NULL,
    diagnostics TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_ocr_page_report_id FOREIGN KEY (report_id) REFERENCES report_document(id),
    UNIQUE KEY uk_report_ocr_page_report_page (report_id, page_number),
    KEY idx_report_ocr_page_report_id (report_id)
);

CREATE TABLE IF NOT EXISTS report_paragraph_atom (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    paragraph_id INT NOT NULL,
    page_number INT NULL,
    section_path VARCHAR(512) NULL,
    paragraph_text MEDIUMTEXT NOT NULL,
    token_count INT NULL,
    diagnostics TEXT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_paragraph_atom_report_id FOREIGN KEY (report_id) REFERENCES report_document(id),
    UNIQUE KEY uk_report_paragraph_atom_report_paragraph (report_id, paragraph_id),
    KEY idx_report_paragraph_atom_report_id (report_id),
    KEY idx_report_paragraph_atom_page (report_id, page_number)
);

CREATE TABLE IF NOT EXISTS report_chunk_diagnostic (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    chunk_uid VARCHAR(64) NULL,
    parent_chunk_uid VARCHAR(64) NULL,
    parent_index INT NULL,
    chunk_index_in_parent INT NULL,
    chunk_type VARCHAR(16) NOT NULL,
    section_path VARCHAR(512) NULL,
    token_count INT NULL,
    start_paragraph_id INT NULL,
    end_paragraph_id INT NULL,
    start_page_number INT NULL,
    end_page_number INT NULL,
    kept BOOLEAN NOT NULL DEFAULT FALSE,
    filter_reason VARCHAR(255) NULL,
    diagnostics TEXT NULL,
    chunk_text MEDIUMTEXT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_chunk_diagnostic_report_id FOREIGN KEY (report_id) REFERENCES report_document(id),
    KEY idx_report_chunk_diagnostic_report_id (report_id),
    KEY idx_report_chunk_diagnostic_kept (kept),
    KEY idx_report_chunk_diagnostic_chunk_uid (chunk_uid)
);

CREATE TABLE IF NOT EXISTS report_ingest_failure (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    filename VARCHAR(255) NULL,
    title VARCHAR(255) NULL,
    source VARCHAR(255) NULL,
    institution VARCHAR(255) NULL,
    stage VARCHAR(64) NOT NULL,
    error_message TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    KEY idx_report_ingest_failure_stage (stage),
    KEY idx_report_ingest_failure_created_at (created_at)
);

CREATE TABLE IF NOT EXISTS ingest_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_uid VARCHAR(64) NOT NULL,
    report_id BIGINT NULL,
    report_title_snapshot VARCHAR(255) NULL,
    title_search_key VARCHAR(255) NULL,
    source VARCHAR(255) NULL,
    institution VARCHAR(255) NULL,
    publish_date DATE NULL,
    theme_tags VARCHAR(1024) NULL,
    industry_tags VARCHAR(1024) NULL,
    company_tags VARCHAR(1024) NULL,
    ticker_tags VARCHAR(1024) NULL,
    author_tags VARCHAR(1024) NULL,
    original_filename VARCHAR(255) NULL,
    file_path VARCHAR(1024) NOT NULL,
    ocr_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    chunk_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    vector_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    ocr_attempt_count INT NOT NULL DEFAULT 0,
    chunk_attempt_count INT NOT NULL DEFAULT 0,
    vector_attempt_count INT NOT NULL DEFAULT 0,
    next_run_at TIMESTAMP NOT NULL,
    priority INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    last_error_message VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_ingest_job_uid (job_uid),
    KEY idx_ingest_job_next_run_at (next_run_at),
    KEY idx_ingest_job_ocr_status (ocr_status),
    KEY idx_ingest_job_chunk_status (chunk_status),
    KEY idx_ingest_job_vector_status (vector_status),
    KEY idx_ingest_job_report_id (report_id),
    KEY idx_ingest_job_title_search_key (title_search_key)
);

CREATE TABLE IF NOT EXISTS report_ingest_stage_event (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_uid VARCHAR(64) NOT NULL,
    report_id BIGINT NULL,
    report_title_snapshot VARCHAR(255) NULL,
    title_search_key VARCHAR(255) NULL,
    stage VARCHAR(32) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    model_name VARCHAR(128) NULL,
    input_size INT NULL,
    output_size INT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP NULL,
    duration_ms BIGINT NULL,
    error_code VARCHAR(64) NULL,
    error_message_short VARCHAR(512) NULL,
    trace_id VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_stage_event_job_stage_attempt (job_uid, stage, attempt),
    KEY idx_stage_event_report_id (report_id),
    KEY idx_stage_event_title_search_key (title_search_key),
    KEY idx_stage_event_stage_status (stage, status),
    KEY idx_stage_event_created_at (created_at)
);

CREATE TABLE IF NOT EXISTS theme_dictionary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    theme_code VARCHAR(64) NOT NULL,
    theme_name VARCHAR(128) NOT NULL,
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    description VARCHAR(512) NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_theme_dictionary_code_version (theme_code, version),
    KEY idx_theme_dictionary_status (status),
    KEY idx_theme_dictionary_code (theme_code)
);

CREATE TABLE IF NOT EXISTS theme_term (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    term_text VARCHAR(128) NOT NULL,
    normalized_term VARCHAR(128) NOT NULL,
    term_type VARCHAR(32) NOT NULL DEFAULT 'ALIAS',
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_theme_term_normalized_version (normalized_term, version),
    KEY idx_theme_term_status (status),
    KEY idx_theme_term_type (term_type)
);

CREATE TABLE IF NOT EXISTS theme_term_relation (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    theme_code VARCHAR(64) NOT NULL,
    term_id BIGINT NOT NULL,
    relation_type VARCHAR(32) NOT NULL DEFAULT 'ALIAS',
    weight DECIMAL(10,4) NOT NULL DEFAULT 1.0000,
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_theme_term_relation_term_id FOREIGN KEY (term_id) REFERENCES theme_term(id),
    UNIQUE KEY uk_theme_term_relation_theme_term_version (theme_code, term_id, version),
    KEY idx_theme_term_relation_theme (theme_code, status),
    KEY idx_theme_term_relation_term (term_id)
);

CREATE TABLE IF NOT EXISTS industry_dictionary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    industry_code VARCHAR(64) NOT NULL,
    industry_name VARCHAR(128) NOT NULL,
    parent_code VARCHAR(64) NULL,
    level_no INT NOT NULL DEFAULT 1,
    aliases VARCHAR(512) NULL,
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_industry_dictionary_code_version (industry_code, version),
    KEY idx_industry_dictionary_status (status),
    KEY idx_industry_dictionary_parent (parent_code)
);

CREATE TABLE IF NOT EXISTS security_dictionary (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    security_code VARCHAR(32) NOT NULL,
    ticker VARCHAR(32) NOT NULL,
    exchange VARCHAR(16) NOT NULL,
    company_name VARCHAR(128) NOT NULL,
    industry_code VARCHAR(64) NULL,
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_security_dictionary_ticker_version (ticker, version),
    KEY idx_security_dictionary_company (company_name),
    KEY idx_security_dictionary_status (status),
    KEY idx_security_dictionary_industry (industry_code)
);

CREATE TABLE IF NOT EXISTS security_alias (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    security_code VARCHAR(32) NOT NULL,
    alias_text VARCHAR(128) NOT NULL,
    normalized_alias VARCHAR(128) NOT NULL,
    version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_security_alias_normalized_version (normalized_alias, version),
    KEY idx_security_alias_security (security_code),
    KEY idx_security_alias_status (status)
);

CREATE TABLE IF NOT EXISTS report_chunk_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    chunk_uid VARCHAR(64) NOT NULL,
    tag_type VARCHAR(32) NOT NULL,
    tag_code VARCHAR(64) NOT NULL,
    tag_name VARCHAR(128) NOT NULL,
    confidence DECIMAL(10,4) NOT NULL DEFAULT 1.0000,
    source VARCHAR(32) NOT NULL DEFAULT 'DICTIONARY',
    dictionary_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_report_chunk_tag_uid_type_code_version (chunk_uid, tag_type, tag_code, dictionary_version),
    KEY idx_report_chunk_tag_report_id (report_id),
    KEY idx_report_chunk_tag_type_code (tag_type, tag_code),
    KEY idx_report_chunk_tag_chunk_uid (chunk_uid)
);

CREATE TABLE IF NOT EXISTS report_document_tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    report_id BIGINT NOT NULL,
    tag_type VARCHAR(32) NOT NULL,
    tag_code VARCHAR(64) NOT NULL,
    tag_name VARCHAR(128) NOT NULL,
    confidence DECIMAL(10,4) NOT NULL DEFAULT 1.0000,
    source VARCHAR(32) NOT NULL DEFAULT 'CHUNK_AGGREGATION',
    dictionary_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_report_document_tag_report_type_code_version (report_id, tag_type, tag_code, dictionary_version),
    KEY idx_report_document_tag_type_code (tag_type, tag_code),
    KEY idx_report_document_tag_report_id (report_id)
);

CREATE TABLE IF NOT EXISTS report_chunk_tag_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_uid VARCHAR(64) NOT NULL,
    report_id BIGINT NOT NULL,
    chunk_uid VARCHAR(64) NOT NULL,
    dictionary_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    last_error VARCHAR(512) NULL,
    next_run_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_report_chunk_tag_job_uid (job_uid),
    UNIQUE KEY uk_report_chunk_tag_job_chunk_version (chunk_uid, dictionary_version),
    KEY idx_report_chunk_tag_job_status_next (status, next_run_at),
    KEY idx_report_chunk_tag_job_report_id (report_id)
);

CREATE TABLE IF NOT EXISTS report_vector_metadata_sync_job (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    job_uid VARCHAR(64) NOT NULL,
    report_id BIGINT NOT NULL,
    chunk_uid VARCHAR(64) NOT NULL,
    metadata_version VARCHAR(32) NOT NULL DEFAULT 'v1',
    tag_snapshot_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INT NOT NULL DEFAULT 0,
    max_attempts INT NOT NULL DEFAULT 3,
    last_error VARCHAR(512) NULL,
    next_run_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NULL,
    finished_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    UNIQUE KEY uk_vector_metadata_sync_job_uid (job_uid),
    UNIQUE KEY uk_vector_metadata_sync_job_chunk_hash (chunk_uid, tag_snapshot_hash),
    KEY idx_vector_metadata_sync_job_status_next (status, next_run_at),
    KEY idx_vector_metadata_sync_job_report_id (report_id)
);

INSERT INTO theme_dictionary(theme_code, theme_name, version, status, description, created_at, updated_at)
SELECT 'STORAGE', '储能', 'v1', 'ACTIVE', '储能、电化学储能、新型储能和储能系统主题', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_dictionary WHERE theme_code = 'STORAGE' AND version = 'v1');

INSERT INTO theme_dictionary(theme_code, theme_name, version, status, description, created_at, updated_at)
SELECT 'AI_COMPUTE', 'AI算力', 'v1', 'ACTIVE', 'AI算力、数据中心和算力产业链主题', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_dictionary WHERE theme_code = 'AI_COMPUTE' AND version = 'v1');

INSERT INTO theme_dictionary(theme_code, theme_name, version, status, description, created_at, updated_at)
SELECT 'PORT', '港口', 'v1', 'ACTIVE', '港口运营、吞吐量和陆海通道主题', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_dictionary WHERE theme_code = 'PORT' AND version = 'v1');

INSERT INTO theme_dictionary(theme_code, theme_name, version, status, description, created_at, updated_at)
SELECT 'MILITARY', '军工', 'v1', 'ACTIVE', '国防军工和装备制造主题', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_dictionary WHERE theme_code = 'MILITARY' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '储能', '储能', 'REQUIRED', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '储能' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '电化学储能', '电化学储能', 'ALIAS', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '电化学储能' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '新型储能', '新型储能', 'ALIAS', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '新型储能' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '储能系统', '储能系统', 'ALIAS', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '储能系统' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT 'AI算力', 'ai算力', 'REQUIRED', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = 'ai算力' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '算力', '算力', 'ALIAS', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '算力' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '港口', '港口', 'REQUIRED', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '港口' AND version = 'v1');

INSERT INTO theme_term(term_text, normalized_term, term_type, version, status, created_at, updated_at)
SELECT '军工', '军工', 'REQUIRED', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM theme_term WHERE normalized_term = '军工' AND version = 'v1');

INSERT INTO theme_term_relation(theme_code, term_id, relation_type, weight, version, status, created_at, updated_at)
SELECT 'STORAGE', id, term_type, 1.0000, 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM theme_term
WHERE normalized_term IN ('储能', '电化学储能', '新型储能', '储能系统')
  AND NOT EXISTS (SELECT 1 FROM theme_term_relation WHERE theme_code = 'STORAGE' AND term_id = theme_term.id AND version = 'v1');

INSERT INTO theme_term_relation(theme_code, term_id, relation_type, weight, version, status, created_at, updated_at)
SELECT 'AI_COMPUTE', id, term_type, 1.0000, 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM theme_term
WHERE normalized_term IN ('ai算力', '算力')
  AND NOT EXISTS (SELECT 1 FROM theme_term_relation WHERE theme_code = 'AI_COMPUTE' AND term_id = theme_term.id AND version = 'v1');

INSERT INTO theme_term_relation(theme_code, term_id, relation_type, weight, version, status, created_at, updated_at)
SELECT 'PORT', id, term_type, 1.0000, 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM theme_term
WHERE normalized_term = '港口'
  AND NOT EXISTS (SELECT 1 FROM theme_term_relation WHERE theme_code = 'PORT' AND term_id = theme_term.id AND version = 'v1');

INSERT INTO theme_term_relation(theme_code, term_id, relation_type, weight, version, status, created_at, updated_at)
SELECT 'MILITARY', id, term_type, 1.0000, 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP FROM theme_term
WHERE normalized_term = '军工'
  AND NOT EXISTS (SELECT 1 FROM theme_term_relation WHERE theme_code = 'MILITARY' AND term_id = theme_term.id AND version = 'v1');

INSERT INTO industry_dictionary(industry_code, industry_name, parent_code, level_no, aliases, version, status, created_at, updated_at)
SELECT 'POWER_EQUIPMENT', '电力设备', NULL, 1, '新能源,储能,电池', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM industry_dictionary WHERE industry_code = 'POWER_EQUIPMENT' AND version = 'v1');

INSERT INTO industry_dictionary(industry_code, industry_name, parent_code, level_no, aliases, version, status, created_at, updated_at)
SELECT 'NEW_ENERGY', '新能源', NULL, 1, '光伏,风电,储能', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM industry_dictionary WHERE industry_code = 'NEW_ENERGY' AND version = 'v1');

INSERT INTO industry_dictionary(industry_code, industry_name, parent_code, level_no, aliases, version, status, created_at, updated_at)
SELECT 'TRANSPORT_PORT', '港口', NULL, 1, '交通运输,港口运营', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM industry_dictionary WHERE industry_code = 'TRANSPORT_PORT' AND version = 'v1');

INSERT INTO industry_dictionary(industry_code, industry_name, parent_code, level_no, aliases, version, status, created_at, updated_at)
SELECT 'DEFENSE', '国防军工', NULL, 1, '军工,装备', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM industry_dictionary WHERE industry_code = 'DEFENSE' AND version = 'v1');

INSERT INTO security_dictionary(security_code, ticker, exchange, company_name, industry_code, version, status, created_at, updated_at)
SELECT '300750', '300750.SZ', 'SZ', '宁德时代', 'POWER_EQUIPMENT', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM security_dictionary WHERE ticker = '300750.SZ' AND version = 'v1');

INSERT INTO security_dictionary(security_code, ticker, exchange, company_name, industry_code, version, status, created_at, updated_at)
SELECT '000582', '000582.SZ', 'SZ', '北部湾港', 'TRANSPORT_PORT', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM security_dictionary WHERE ticker = '000582.SZ' AND version = 'v1');

INSERT INTO security_alias(security_code, alias_text, normalized_alias, version, status, created_at, updated_at)
SELECT '300750', '宁德时代', '宁德时代', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM security_alias WHERE normalized_alias = '宁德时代' AND version = 'v1');

INSERT INTO security_alias(security_code, alias_text, normalized_alias, version, status, created_at, updated_at)
SELECT '000582', '北部湾港', '北部湾港', 'v1', 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM security_alias WHERE normalized_alias = '北部湾港' AND version = 'v1');
