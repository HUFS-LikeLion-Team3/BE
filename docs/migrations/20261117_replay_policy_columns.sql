-- P1 Replay policy. Manual MySQL migration - do not run blindly.
-- MySQL 8 DDL auto-commits; first back up the DB and inspect both tables.
-- If either column already exists, skip that ADD COLUMN statement.
SELECT DATABASE() AS selected_schema;
SHOW CREATE TABLE learning_sessions;
SHOW CREATE TABLE source_documents;
SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'learning_sessions' AND COLUMN_NAME = 'replay_reference_at')
    OR (TABLE_NAME = 'source_documents' AND COLUMN_NAME = 'available_at'));

-- Run each statement ONCE only when its column is absent:
-- ALTER TABLE learning_sessions ADD COLUMN replay_reference_at DATETIME(6) NULL;
-- ALTER TABLE source_documents ADD COLUMN available_at DATETIME(6) NULL;

-- These are nullable to preserve existing Live sessions/documents. For replay,
-- replay_reference_at is set once at prediction submission (News.reference_at).
-- available_at is optional historical availability metadata; when not recorded,
-- the original published_at remains the fallback publication cutoff.
-- Verify after manually applying:
-- SHOW CREATE TABLE learning_sessions;
-- SHOW CREATE TABLE source_documents;

-- Historical cleanup ONLY after team review:
-- PR #42 could have set results_revealed_at automatically at reflection_pending.
-- Check affected rows before deploying the new explicit-click policy:
SELECT COUNT(*) AS previously_revealed_replay_sessions
FROM learning_sessions
WHERE session_type = 'replay' AND results_revealed_at IS NOT NULL;
-- If the team confirms every matching timestamp came only from the old
-- automatic transition (not from a real user click), reset it in a separately
-- reviewed migration. NEVER run a blanket UPDATE without verification.
