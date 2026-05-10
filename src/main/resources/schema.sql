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
    chunk_text TEXT NOT NULL,
    page_number INT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_report_chunk_report_id FOREIGN KEY (report_id) REFERENCES report_document(id),
    UNIQUE KEY uk_report_chunk_uid (chunk_uid),
    KEY idx_report_chunk_report_id (report_id),
    KEY idx_report_chunk_created_at (created_at)
);

ALTER TABLE report_chunk ADD COLUMN IF NOT EXISTS chunk_uid VARCHAR(64) NULL;
UPDATE report_chunk SET chunk_uid = REPLACE(UUID(), '-', '') WHERE chunk_uid IS NULL;
