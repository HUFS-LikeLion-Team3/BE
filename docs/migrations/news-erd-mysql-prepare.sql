-- 이전 뉴스 API가 만든 MySQL 스키마 전용 이관. 서버를 끄고 백업 후 실행.
-- 기존 테이블을 삭제하지 않습니다. MySQL DDL은 자동 커밋됩니다.
DELIMITER //
CREATE PROCEDURE finsight_news_erd_prepare()
BEGIN
  IF EXISTS (SELECT 1 FROM news_sources GROUP BY source_document_id HAVING COUNT(DISTINCT news_id) > 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='One document is linked to multiple news; resolve before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM news WHERE published_at IS NULL OR reference_at IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fill real published_at/reference_at before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM source_documents WHERE source_type IS NULL OR selection_tier IS NULL OR retrieved_at IS NULL) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Fill approved source metadata before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM news_facts GROUP BY news_id,sort_order HAVING COUNT(*) > 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate fact order; resolve before migration';
  END IF;
  IF EXISTS (SELECT 1 FROM source_documents GROUP BY url,content_hash HAVING COUNT(*) > 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Duplicate source URL/hash; resolve before migration';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='news' AND column_name='created_at') THEN
    ALTER TABLE news ADD COLUMN created_at datetime(6) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='news' AND column_name='updated_at') THEN
    ALTER TABLE news ADD COLUMN updated_at datetime(6) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_documents' AND column_name='news_id') THEN
    ALTER TABLE source_documents ADD COLUMN news_id binary(16) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_documents' AND column_name='created_at') THEN
    ALTER TABLE source_documents ADD COLUMN created_at datetime(6) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_documents' AND column_name='updated_at') THEN
    ALTER TABLE source_documents ADD COLUMN updated_at datetime(6) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='source_documents' AND column_name='content_storage_uri') THEN
    ALTER TABLE source_documents ADD COLUMN content_storage_uri varchar(255) NULL;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='news_facts' AND column_name='source_document_id') THEN
    ALTER TABLE news_facts ADD COLUMN source_document_id binary(16) NULL;
  END IF;
  -- 이전 모델에 생성 시각이 없으므로 이관 시각을 기록합니다. 공개 시각은 보존합니다.
  UPDATE news SET created_at=COALESCE(created_at,UTC_TIMESTAMP(6)),updated_at=COALESCE(updated_at,UTC_TIMESTAMP(6));
  UPDATE source_documents SET created_at=COALESCE(created_at,UTC_TIMESTAMP(6)),updated_at=COALESCE(updated_at,UTC_TIMESTAMP(6));
  UPDATE source_documents d JOIN news_sources s ON s.source_document_id=d.id
    SET d.news_id=s.news_id WHERE d.news_id IS NULL;
  -- Existing fact source was unknown: retain NULL except for the two known samples.
  UPDATE news_facts SET source_document_id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa'
    WHERE id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee1' AND source_document_id IS NULL
    AND EXISTS(SELECT 1 FROM source_documents WHERE id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');
  UPDATE news_facts SET source_document_id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb'
    WHERE id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee2' AND source_document_id IS NULL
    AND EXISTS(SELECT 1 FROM source_documents WHERE id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');
  ALTER TABLE news MODIFY created_at datetime(6) NOT NULL, MODIFY updated_at datetime(6) NOT NULL;
  ALTER TABLE source_documents MODIFY created_at datetime(6) NOT NULL, MODIFY updated_at datetime(6) NOT NULL;
END//
DELIMITER ;
CALL finsight_news_erd_prepare();
DROP PROCEDURE finsight_news_erd_prepare;
