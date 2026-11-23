-- Issue #26: 동일 feedback · 동일 chunk의 복수 실제 인용 허용
-- 적용 대상: MySQL 8, ai_feedback_references
-- 기존 reference 행/PK, ai_feedback_item_references 연결은 수정하거나 삭제하지 않는다.
-- 이 파일은 검증 후 수동으로 DDL을 실행하도록 작성했다.
-- 실제 schema에서 인덱스 이름과 FK 의존성을 반드시 확인할 것.

SELECT DATABASE() AS selected_schema;
SHOW CREATE TABLE ai_feedback_references;

-- 적용 전 인덱스와 유일성 확인
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_feedback_references'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

-- 환경별 적용 SQL을 구분한다.
-- A. 기존 uk_ai_feedback_reference_chunk UNIQUE가 있는 DB:
--    아래 주석 처리된 ADD INDEX + DROP INDEX 예시를 사전 점검 후 실행한다.
-- B. PRIMARY 외 다른 인덱스가 없는 로컬 DB (2026-11-03 확인):
--    실제 DB에서 이름 중복/기존 데이터 중복을 점검한 뒤 다음을 수동 실행한다.
--    ALTER TABLE ai_feedback_references
--      ADD CONSTRAINT uk_ai_feedback_reference_order UNIQUE (ai_feedback_id, sort_order),
--      ADD INDEX idx_ai_feedback_reference_feedback_chunk
--        (ai_feedback_id, source_document_chunk_id);
--    확인된 로컬 DB에서 실행 완료. 다른 DB에 그대로 반복 실행하지 않는다.
-- C. 이미 order UNIQUE + feedback_chunk 비유일 인덱스가 있으면 추가 DDL은 불필요하다.
-- 기존 DB에 주문서·출처 등 관련 다른 제약이 있으면 각각 검토해야 한다.

-- 변경 전 데이터/링크 건수 기록 (변경 후 동일해야 함)
SELECT COUNT(*) AS reference_rows FROM ai_feedback_references;
SELECT COUNT(*) AS item_reference_links FROM ai_feedback_item_references;

-- 아래 DDL은 실제 DB에서 'uk_ai_feedback_reference_chunk' 유일 인덱스가 존재하고
-- 'idx_ai_feedback_reference_feedback_chunk' 인덱스가 아직 없음을 확인한 뒤에만 실행.
-- 이미 적용된 환경에서는 절대 재실행하지 않는다.
-- MySQL DDL은 자동 커밋되므로 운영 적용 전 백업이 필요하다.
--
-- ALTER TABLE ai_feedback_references
--   ADD INDEX idx_ai_feedback_reference_feedback_chunk
--     (ai_feedback_id, source_document_chunk_id),
--   DROP INDEX uk_ai_feedback_reference_chunk;

-- 적용 후 검증: 동일 chunk의 다른 reference가 허용되어야 하고,
-- (ai_feedback_id, sort_order)의 유일성은 그대로 유지되어야 한다.
SELECT INDEX_NAME, NON_UNIQUE, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'ai_feedback_references'
ORDER BY INDEX_NAME, SEQ_IN_INDEX;

SELECT COUNT(*) AS reference_rows FROM ai_feedback_references;
SELECT COUNT(*) AS item_reference_links FROM ai_feedback_item_references;
