-- Hibernate update가 기존 컬럼의 nullable/default/check를 갱신하지 않으므로 명시적으로 적용.
-- prepare 이후 새 서버로 테이블을 생성한 뒤 서버를 중지하고 실행합니다.
ALTER TABLE news
 MODIFY published_at datetime(6) NOT NULL,
 MODIFY reference_at datetime(6) NOT NULL,
 MODIFY content_type varchar(255) NOT NULL DEFAULT 'live',
 MODIFY replay_status varchar(255) NOT NULL DEFAULT 'not_eligible',
 MODIFY status varchar(255) NOT NULL DEFAULT 'draft';
ALTER TABLE source_documents
 MODIFY source_type varchar(255) NOT NULL,
 MODIFY selection_tier varchar(255) NOT NULL,
 MODIFY retrieved_at datetime(6) NOT NULL,
 MODIFY is_primary bit NOT NULL DEFAULT b'0';
ALTER TABLE users MODIFY auth_provider varchar(30) NOT NULL DEFAULT 'kakao';

DELIMITER //
CREATE PROCEDURE finsight_news_erd_finalize()
BEGIN
 IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
   WHERE constraint_schema=DATABASE() AND table_name='news' AND constraint_name='ck_news_erd_values') THEN
  ALTER TABLE news ADD CONSTRAINT ck_news_erd_values CHECK (
   content_type IN ('live','replay') AND replay_status IN ('not_eligible','eligible','featured')
   AND status IN ('draft','published','archived'));
 END IF;
 IF NOT EXISTS (SELECT 1 FROM information_schema.table_constraints
   WHERE constraint_schema=DATABASE() AND table_name='source_documents' AND constraint_name='ck_source_erd_values') THEN
  ALTER TABLE source_documents ADD CONSTRAINT ck_source_erd_values CHECK (
   source_type IN ('original_article','official_statistic','government_release','central_bank_release','research_report')
   AND selection_tier IN ('primary_official','original_article','trusted_research'));
 END IF;
END//
DELIMITER ;
CALL finsight_news_erd_finalize();
DROP PROCEDURE finsight_news_erd_finalize;
