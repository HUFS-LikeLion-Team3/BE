-- 운영 DB에 원/달러 환율·금·WTI 대상을 추가하고 명세의 방향 문구를 맞춘다.
-- 시세 수집 경로: USDKRW·GOLD = Twelve Data 일봉(TwelveDataClient), WTI = EIA 공개 일별 현물(EiaWtiClient).
-- 가격은 넣지 않는다. 같은 code 또는 id가 있으면 INSERT하지 않으므로 반복 실행해도 안전하다.
-- 주의: AI 자료 패키지의 marketSearchTerms에 없는 대상을 고른 세션은 AI 피드백 요청이 거절된다.
--       AI 패키지 갱신·재등록과 함께 반영한다.

-- 실행 전 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('USDKRW', 'GOLD', 'WTI', 'US10Y');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa','USDKRW','달러/원 환율 (제공처 일일 마감)','fx','exchange_rate','{"up":"환율 상승(원화 약세)","down":"환율 하락(원화 강세)","neutral":"큰 방향성 없음"}','KRW','TDFX','Australia/Sydney','USD/KRW',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='USDKRW' OR id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb','GOLD','금 현물 (제공처 일일 마감)','commodity','commodity','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD/troy_oz','TDGD','Australia/Sydney','XAU/USD',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='GOLD' OR id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'cccccccccccc4ccc8ccccccccccccccc','WTI','WTI 원유 현물 (EIA 일일 기준)','commodity','commodity','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD/barrel','TDWT','Australia/Sydney','WTI/USD',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='WTI' OR id=X'cccccccccccc4ccc8ccccccccccccccc');

-- 화면 명세 1.3의 금리 방향 문구. 기존 세션·예측 값(up/down/neutral)은 바뀌지 않고 표시 문구만 바뀐다.
UPDATE market_targets
SET direction_labels='{"up":"금리 상승","down":"금리 하락","neutral":"큰 방향성 없음"}', updated_at=CURRENT_TIMESTAMP
WHERE code='US10Y' AND target_type='yield';

-- 실행 후 확인
SELECT code, display_name, direction_labels, is_active
FROM market_targets WHERE code IN ('USDKRW', 'GOLD', 'WTI', 'US10Y');
