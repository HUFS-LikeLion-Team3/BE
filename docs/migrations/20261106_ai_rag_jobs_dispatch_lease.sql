-- Issue #27: atomic, restart-recoverable AI job dispatch lease.
-- MANUAL MIGRATION: ALTER TABLE is intentionally commented out.
--
-- Deployment procedure:
-- 1. BE 배포 전 대상 MySQL DB를 백업한다.
-- 2. SELECT DATABASE() 및 information_schema로 기존 컬럼을 확인한다.
-- 3. 두 컬럼이 모두 없고 스키마가 확인되면 아래 ALTER TABLE을 수동 실행한다.
-- 4. 한 컬럼만 존재하거나 타입이 다르면 실행을 중단하고 별도 검토한다.
-- 5. SHOW CREATE TABLE ai_rag_jobs로 적용 결과를 확인한다.
-- 6. 두 컬럼이 정상적으로 존재하는 것을 확인한 후 BE를 배포한다.
--
-- MySQL DDL은 자동 커밋되므로 ROLLBACK으로 되돌릴 수 없다.

SELECT DATABASE() AS selected_schema;

SHOW CREATE TABLE ai_rag_jobs;

SELECT COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_rag_jobs'
  AND COLUMN_NAME IN ('dispatch_owner', 'dispatch_lease_until');

-- Only if BOTH columns are absent and schema is confirmed:
-- ALTER TABLE ai_rag_jobs
--   ADD COLUMN dispatch_owner VARCHAR(36) NULL,
--   ADD COLUMN dispatch_lease_until DATETIME(6) NULL;

-- If either column already exists, do not run the combined ALTER.
-- Do not alter existing immutable request_json / job_id / result_hash values.
-- A NULL lease is idle; an expired lease can be reclaimed under a row lock.