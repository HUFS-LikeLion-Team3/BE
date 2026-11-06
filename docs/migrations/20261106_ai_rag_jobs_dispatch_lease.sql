-- Issue #27: atomic, restart-recoverable AI job dispatch lease.
-- MANUAL REVIEW ONLY: this script deliberately does not execute ALTER TABLE.
-- Apply to the SAME MySQL schema used by BE after backup and team review.
-- MySQL DDL auto-commits; ROLLBACK does not undo the ALTER.

SELECT DATABASE() AS selected_schema;
SHOW CREATE TABLE ai_rag_jobs;
SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'ai_rag_jobs'
  AND COLUMN_NAME IN ('dispatch_owner', 'dispatch_lease_until');

-- Only if BOTH columns are absent and schema is confirmed:
-- ALTER TABLE ai_rag_jobs
--   ADD COLUMN dispatch_owner VARCHAR(36) NULL,
--   ADD COLUMN dispatch_lease_until DATETIME(6) NULL;

-- If either column already exists, compare its type before migrating.
-- Do not alter existing immutable request_json / job_id / result_hash values.
-- A NULL lease is idle; an expired lease can be reclaimed under a row lock.
