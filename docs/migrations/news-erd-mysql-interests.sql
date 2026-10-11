-- prepare SQL → 새 서버 1회 실행 (Hibernate 구조 생성) → 서버 중지 후 실행.
-- user_interests 유일 키를 활용해 반복 실행 시 기존 관심사는 보존합니다.
INSERT INTO user_interests (id,user_id,interest_type,interest_key,created_at)
SELECT UNHEX(REPLACE(UUID(),'-','')),m.user_id,'market',
 CASE m.market WHEN 'US' THEN 'us_equity' WHEN 'KR' THEN 'korea_equity' ELSE m.market END,UTC_TIMESTAMP(6)
FROM user_interest_markets m JOIN users u ON u.id=m.user_id
WHERE NOT EXISTS (SELECT 1 FROM user_interests i WHERE i.user_id=m.user_id AND i.interest_type='market'
 AND i.interest_key=CASE m.market WHEN 'US' THEN 'us_equity' WHEN 'KR' THEN 'korea_equity' ELSE m.market END);

INSERT INTO user_interests (id,user_id,interest_type,interest_key,created_at)
SELECT UNHEX(REPLACE(UUID(),'-','')),t.user_id,'topic',
 CASE t.topic WHEN 'rates' THEN 'rate' ELSE t.topic END,UTC_TIMESTAMP(6)
FROM user_interest_topics t JOIN users u ON u.id=t.user_id
WHERE NOT EXISTS (SELECT 1 FROM user_interests i WHERE i.user_id=t.user_id AND i.interest_type='topic'
 AND i.interest_key=CASE t.topic WHEN 'rates' THEN 'rate' ELSE t.topic END);

-- 이전 태그 값 중 시장 후보로 바꿀 수 있는 것은 기존 대상의 시장 카테고리에 연결.
-- sample 프로필이 생성한 SAMPLE_US/SAMPLE_KR 대상을 통해 샘플 뉴스 연결을 복원.
-- 비샘플 뉴스의 후보 대상은 큐레이션 담당자가 확정해야 하므로 임의 생성하지 않습니다.
-- 이미 보존 중인 이전 테이블은 여기서 DROP하지 않습니다.
