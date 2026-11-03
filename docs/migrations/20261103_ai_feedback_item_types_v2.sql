
-- Issue #26: AI feedback explanation v2
-- Manual migration only.
-- 기존 ENUM 9개 유지, 신규 ENUM 6개 추가.
-- 실제 DB와 기존 제약조건 확인 후 적용한다.

-- 적용 대상 데이터베이스 확인
SELECT DATABASE() AS selected_schema;

-- 변경 전 컬럼 확인
SHOW COLUMNS FROM ai_feedback_items LIKE 'item_type';

-- 다른 환경에 적용할 경우 아래 ALTER TABLE의 주석을 해제한다.
-- 현재 로컬 DB에는 이미 적용되었으므로 다시 실행하지 않는다.

-- ALTER TABLE ai_feedback_items
-- MODIFY COLUMN item_type ENUM(
--     'final_co_observed_factors',
--     'final_next_hypothesis',
--     'final_observed_flow',
--     'final_reflection_improvement',
--     'flow_summary',
--     'initial_counter_scenario',
--     'initial_factor_to_review',
--     'initial_uncertainty',
--     'interpretation_limit',
--     'news_analysis',
--     'initial_market_impact',
--     'initial_prediction_review',
--     'final_market_explanation',
--     'final_prediction_review',
--     'final_takeaway'
-- ) NOT NULL;

-- 변경 후 확인
SHOW COLUMNS FROM ai_feedback_items LIKE 'item_type';
