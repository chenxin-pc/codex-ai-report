CREATE TABLE IF NOT EXISTS report_document (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    title VARCHAR(255) NOT NULL,
    source VARCHAR(255) NOT NULL,
    institution VARCHAR(255) NULL,
    publish_date DATE NULL,
    created_at TIMESTAMP NOT NULL
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
