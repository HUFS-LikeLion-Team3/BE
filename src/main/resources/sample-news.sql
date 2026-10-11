-- ERD 기반 API 확인용 가상 데이터. 실제 금융 뉴스가 아닙니다.
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at,created_at,updated_at)
SELECT X'11111111111141118111111111111111','[샘플] 미국 기준금리 동결','rate','API 테스트를 위한 가상 금리 뉴스입니다.','live','not_eligible','published','2026-10-01 09:00:00','2026-10-01 09:00:00','2026-10-01 09:00:00','2026-10-01 09:00:00','2026-10-01 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'11111111111141118111111111111111');
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at,created_at,updated_at)
SELECT X'22222222222242228222222222222222','[샘플] 한국 기준금리 동결','rate','API 테스트를 위한 가상 금리 뉴스입니다.','live','not_eligible','published','2026-10-02 09:00:00','2026-10-02 09:00:00','2026-10-02 09:00:00','2026-10-02 09:00:00','2026-10-02 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'22222222222242228222222222222222');
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at,created_at,updated_at)
SELECT X'33333333333343338333333333333333','[샘플] 과거 금리 결정 돌아보기','rate','API 테스트를 위한 가상 금리 뉴스입니다.','replay','not_eligible','published','2026-09-01 09:00:00','2026-09-01 09:00:00','2026-09-01 09:00:00','2026-09-01 09:00:00','2026-09-01 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'33333333333343338333333333333333');
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at,created_at,updated_at)
SELECT X'44444444444444448444444444444444','[샘플] 비공개 뉴스','rate','API 테스트를 위한 가상 금리 뉴스입니다.','live','not_eligible','draft','2026-10-03 09:00:00','2026-10-03 09:00:00','2026-10-03 09:00:00','2026-10-03 09:00:00','2026-10-03 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'44444444444444448444444444444444');
INSERT INTO source_documents (id,news_id,source_type,selection_tier,publisher,title,url,published_at,is_primary,content_hash,retrieved_at,created_at,updated_at)
SELECT X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa',X'11111111111141118111111111111111','central_bank_release','primary_official','Sample Publisher','[샘플] 금리 발표 출처','https://example.com/sample-1','2026-10-01 09:00:00',true,'sample-content-hash-1','2026-10-01 09:10:00','2026-10-01 09:10:00','2026-10-01 09:10:00'
WHERE NOT EXISTS (SELECT 1 FROM source_documents WHERE id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');
INSERT INTO news_facts (id,news_id,source_document_id,label,value_text,unit,as_of_at,sort_order)
SELECT X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee1',X'11111111111141118111111111111111',X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa','기준금리','4.25','%','2026-10-01 09:00:00',1
WHERE NOT EXISTS (SELECT 1 FROM news_facts WHERE id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee1');
INSERT INTO source_documents (id,news_id,source_type,selection_tier,publisher,title,url,published_at,is_primary,content_hash,retrieved_at,created_at,updated_at)
SELECT X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb',X'22222222222242228222222222222222','central_bank_release','primary_official','Sample Publisher','[샘플] 금리 발표 출처','https://example.com/sample-2','2026-10-01 09:00:00',true,'sample-content-hash-2','2026-10-01 09:10:00','2026-10-01 09:10:00','2026-10-01 09:10:00'
WHERE NOT EXISTS (SELECT 1 FROM source_documents WHERE id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');
INSERT INTO news_facts (id,news_id,source_document_id,label,value_text,unit,as_of_at,sort_order)
SELECT X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee2',X'22222222222242228222222222222222',X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb','기준금리','2.50','%','2026-10-01 09:00:00',1
WHERE NOT EXISTS (SELECT 1 FROM news_facts WHERE id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee2');
DELETE FROM news_facts WHERE id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeee3' AND news_id=X'22222222222242228222222222222222';
INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'55555555555545558555555555555555','SAMPLE_US','[샘플] 미국 지수','us_equity','index','{"up":"상승","down":"하락","neutral":"보합"}','point','SAMPLE','America/New_York','SAMPLE',true,'2026-10-01 09:00:00','2026-10-01 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE id=X'55555555555545558555555555555555');
INSERT INTO news_target_candidates (news_id,market_target_id,rationale,sort_order) SELECT X'11111111111141118111111111111111',X'55555555555545558555555555555555','가상 대상',1 WHERE NOT EXISTS (SELECT 1 FROM news_target_candidates WHERE news_id=X'11111111111141118111111111111111' AND market_target_id=X'55555555555545558555555555555555');
INSERT INTO news_target_candidates (news_id,market_target_id,rationale,sort_order) SELECT X'33333333333343338333333333333333',X'55555555555545558555555555555555','가상 대상',1 WHERE NOT EXISTS (SELECT 1 FROM news_target_candidates WHERE news_id=X'33333333333343338333333333333333' AND market_target_id=X'55555555555545558555555555555555');
INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'66666666666646668666666666666666','SAMPLE_KR','[샘플] 한국 지수','korea_equity','index','{"up":"상승","down":"하락","neutral":"보합"}','point','SAMPLE','Asia/Seoul','SAMPLE',true,'2026-10-01 09:00:00','2026-10-01 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE id=X'66666666666646668666666666666666');
INSERT INTO news_target_candidates (news_id,market_target_id,rationale,sort_order) SELECT X'22222222222242228222222222222222',X'66666666666646668666666666666666','가상 대상',1 WHERE NOT EXISTS (SELECT 1 FROM news_target_candidates WHERE news_id=X'22222222222242228222222222222222' AND market_target_id=X'66666666666646668666666666666666');
INSERT INTO news_target_candidates (news_id,market_target_id,rationale,sort_order) SELECT X'44444444444444448444444444444444',X'66666666666646668666666666666666','가상 대상',1 WHERE NOT EXISTS (SELECT 1 FROM news_target_candidates WHERE news_id=X'44444444444444448444444444444444' AND market_target_id=X'66666666666646668666666666666666');
