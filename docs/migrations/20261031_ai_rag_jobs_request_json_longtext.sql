-- #25: manual, reviewed schema migration plan for existing MySQL databases.
-- This file is NOT auto-executed. Ask the BE team to review before applying.
-- IMPORTANT: the ALTER statement is intentionally commented out for safety.
-- This avoids accidentally changing the currently selected database when
-- pasting/running the entire file in MySQL Workbench.

-- Step 1: verify the target database explicitly.
SELECT DATABASE() AS selected_schema;
SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_rag_jobs'
  AND COLUMN_NAME = 'request_json';

-- Step 2: after verifying the intended DB and obtaining team approval,
-- uncomment and execute this single DDL statement in a maintenance window.
-- MySQL ALTER TABLE may acquire a lock and is not rolled back by ROLLBACK.
-- ALTER TABLE ai_rag_jobs MODIFY COLUMN request_json LONGTEXT NULL;

-- Step 3: verify the column is now LONGTEXT and other constraints are unchanged.
-- SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE
-- FROM information_schema.COLUMNS
-- WHERE TABLE_SCHEMA = DATABASE()
--   AND TABLE_NAME = 'ai_rag_jobs'
--   AND COLUMN_NAME = 'request_json';
-- SHOW CREATE TABLE ai_rag_jobs;
