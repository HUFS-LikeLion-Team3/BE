-- Issue #26: original approved result must survive blocked/restart.
-- MANUAL migration only; intentionally no executable ALTER in this script.
-- Confirm correct schema, existing ai_rag_jobs structure and backup first.
-- MySQL DDL auto-commits and cannot be undone with ROLLBACK.

SELECT DATABASE() AS selected_schema;
SHOW CREATE TABLE ai_rag_jobs;

SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_jobs'
  AND COLUMN_NAME IN (
    'approved_result_json', 'result_hash', 'approved_result_captured_at'
  );

-- ONLY IF ALL THREE COLUMNS DO NOT EXIST, after team schema review:
-- ALTER TABLE ai_rag_jobs
--   ADD COLUMN approved_result_json LONGTEXT NULL,
--   ADD COLUMN result_hash VARCHAR(128) NULL,
--   ADD COLUMN approved_result_captured_at DATETIME(6) NULL;

-- If ANY column exists, compare the actual definition and prepare a
-- separate reviewed migration rather than blindly applying the ALTER.
-- Once applied, repeat the information_schema query and check row counts.
-- No existing request_json, job_id, package_id or reference row is modified.
