-- 공개 무료 지수 시세가 없는 Russell 2000·필라델피아 반도체지수·달러 인덱스는 추종 ETF(IWM·SOXX·UUP)로,
-- 구리도 Twelve Data 현 요금제에서 현물(HG1)을 받을 수 없어 추종 ETF(CPER, NYSE Arca)로 추가한다. 화면 표시명에 ETF임을 밝힌다.
-- 가격은 넣지 않는다. 같은 code 또는 id가 있으면 INSERT하지 않으므로 반복 실행해도 안전하다.
-- 주의: AI 자료 패키지의 marketSearchTerms에 없는 대상은 AI 피드백 요청이 거절된다. 패키지 갱신과 함께 반영한다.

-- 실행 전 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('IWM', 'SOXX', 'UUP', 'COPPER');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'de8f2c5d77ed4399bacde748eaec9f7e','IWM','Russell 2000 추종 ETF (IWM)','us_equity','etf','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','ARCX','America/New_York','IWM',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='IWM' OR id=X'de8f2c5d77ed4399bacde748eaec9f7e');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'722a021c991a4b788823432be54e088c','SOXX','필라델피아 반도체지수 추종 ETF (SOXX)','sector','etf','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','XNAS','America/New_York','SOXX',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='SOXX' OR id=X'722a021c991a4b788823432be54e088c');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'497560f69c59496190b431c9f2740ff4','UUP','달러 인덱스 추종 ETF (UUP)','fx','etf','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','ARCX','America/New_York','UUP',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='UUP' OR id=X'497560f69c59496190b431c9f2740ff4');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'4983f57f0c694e4b8d3fab08912f7b4b','COPPER','구리 추종 ETF (CPER)','commodity','etf','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','ARCX','America/New_York','CPER',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='COPPER' OR id=X'4983f57f0c694e4b8d3fab08912f7b4b');

-- 실행 후 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('IWM', 'SOXX', 'UUP', 'COPPER');
