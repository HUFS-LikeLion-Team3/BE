-- API 확인용 가상 데이터입니다. 실제 금융 뉴스가 아닙니다.
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at)
SELECT X'11111111111141118111111111111111','[샘플] 미국 기준금리 동결','rate','API 테스트를 위한 가상 금리 뉴스입니다.','live','not_eligible','published','2026-10-01 09:00:00','2026-10-01 09:00:00','2026-10-01 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'11111111111141118111111111111111');
INSERT INTO news_markets (news_id,market) SELECT X'11111111111141118111111111111111','US' WHERE NOT EXISTS (SELECT 1 FROM news_markets WHERE news_id=X'11111111111141118111111111111111' AND market='US');
INSERT INTO news_topics (news_id,topic) SELECT X'11111111111141118111111111111111','rates' WHERE NOT EXISTS (SELECT 1 FROM news_topics WHERE news_id=X'11111111111141118111111111111111' AND topic='rates');
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at)
SELECT X'22222222222242228222222222222222','[샘플] 한국 기준금리 동결','rate','API 테스트를 위한 가상 금리 뉴스입니다.','live','not_eligible','published','2026-10-02 09:00:00','2026-10-02 09:00:00','2026-10-02 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'22222222222242228222222222222222');
INSERT INTO news_markets (news_id,market) SELECT X'22222222222242228222222222222222','KR' WHERE NOT EXISTS (SELECT 1 FROM news_markets WHERE news_id=X'22222222222242228222222222222222' AND market='KR');
INSERT INTO news_topics (news_id,topic) SELECT X'22222222222242228222222222222222','rates' WHERE NOT EXISTS (SELECT 1 FROM news_topics WHERE news_id=X'22222222222242228222222222222222' AND topic='rates');
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at)
SELECT X'33333333333343338333333333333333','[샘플] 과거 금리 결정 돌아보기','rate','API 테스트를 위한 가상 금리 뉴스입니다.','replay','not_eligible','published','2026-09-01 09:00:00','2026-09-01 09:00:00','2026-09-01 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'33333333333343338333333333333333');
INSERT INTO news_markets (news_id,market) SELECT X'33333333333343338333333333333333','US' WHERE NOT EXISTS (SELECT 1 FROM news_markets WHERE news_id=X'33333333333343338333333333333333' AND market='US');
INSERT INTO news_topics (news_id,topic) SELECT X'33333333333343338333333333333333','rates' WHERE NOT EXISTS (SELECT 1 FROM news_topics WHERE news_id=X'33333333333343338333333333333333' AND topic='rates');
INSERT INTO news (id,title,category,briefing,content_type,replay_status,status,published_at,reference_at,curated_at)
SELECT X'44444444444444448444444444444444','[샘플] 비공개 뉴스','rate','API 테스트를 위한 가상 금리 뉴스입니다.','live','not_eligible','draft','2026-10-03 09:00:00','2026-10-03 09:00:00','2026-10-03 09:00:00'
WHERE NOT EXISTS (SELECT 1 FROM news WHERE id=X'44444444444444448444444444444444');
INSERT INTO news_markets (news_id,market) SELECT X'44444444444444448444444444444444','KR' WHERE NOT EXISTS (SELECT 1 FROM news_markets WHERE news_id=X'44444444444444448444444444444444' AND market='KR');
INSERT INTO news_topics (news_id,topic) SELECT X'44444444444444448444444444444444','rates' WHERE NOT EXISTS (SELECT 1 FROM news_topics WHERE news_id=X'44444444444444448444444444444444' AND topic='rates');
INSERT INTO source_documents (id,source_type,selection_tier,publisher,title,url,published_at,is_primary,content_hash,retrieved_at)
SELECT X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa','central_bank_release','primary_official','Sample Publisher','[샘플] 금리 발표 출처','https://example.com/sample-1','2026-10-01 09:00:00',true,'sample-content-hash-1','2026-10-01 09:10:00'
WHERE NOT EXISTS (SELECT 1 FROM source_documents WHERE id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');
INSERT INTO news_sources (id,news_id,source_document_id) SELECT X'cccccccccccc4ccc8ccccccccccccccc',X'11111111111141118111111111111111',X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa' WHERE NOT EXISTS (SELECT 1 FROM news_sources WHERE news_id=X'11111111111141118111111111111111' AND source_document_id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');
INSERT INTO source_documents (id,source_type,selection_tier,publisher,title,url,published_at,is_primary,content_hash,retrieved_at)
SELECT X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb','central_bank_release','primary_official','Sample Publisher','[샘플] 금리 발표 출처','https://example.com/sample-2','2026-10-01 09:00:00',true,'sample-content-hash-2','2026-10-01 09:10:00'
WHERE NOT EXISTS (SELECT 1 FROM source_documents WHERE id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');
INSERT INTO news_sources (id,news_id,source_document_id) SELECT X'dddddddddddd4ddd8ddddddddddddddd',X'22222222222242228222222222222222',X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb' WHERE NOT EXISTS (SELECT 1 FROM news_sources WHERE news_id=X'22222222222242228222222222222222' AND source_document_id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');
