-- Inspect existing columns in the target MySQL schema.
SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, IS_NULLABLE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'news' AND COLUMN_NAME = 'background_context')
    OR (TABLE_NAME = 'source_documents' AND COLUMN_NAME = 'public_summary'));

-- If Hibernate ddl-auto=update is disabled, run each ALTER only if its column is absent.
-- ALTER TABLE news ADD COLUMN background_context TEXT NULL;
-- ALTER TABLE source_documents ADD COLUMN public_summary TEXT NULL;
