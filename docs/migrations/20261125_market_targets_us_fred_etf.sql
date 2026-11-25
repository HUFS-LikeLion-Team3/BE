-- 미국 지수(S&P 500, NASDAQ 100)를 FRED 일별 종가로, 섹터 ETF(XLE, XLF)를 Twelve Data NYSE Arca 종가로 추가한다.
-- Twelve Data 공개 목록에는 SPX·NDX 지수가 없어 기존 NASDAQ100(NDX/XNAS)은 시세를 받지 못한다. FRED 경로로 바꾼다.
-- 가격은 넣지 않는다. 같은 code 또는 id가 있으면 INSERT하지 않으므로 반복 실행해도 안전하다.
-- 주의: AI 자료 패키지의 marketSearchTerms에 없는 대상은 AI 피드백 요청이 거절된다. 패키지 갱신과 함께 반영한다.

-- 실행 전 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('SPX', 'NASDAQ100', 'XLE', 'XLF');

-- 운영 11/14 등록 NASDAQ100(NDX/XNAS)을 FRED NASDAQ100 시리즈로 전환한다. ID·세션 연결은 유지된다.
UPDATE market_targets
SET calendar_code='FRED', data_symbol='NASDAQ100', updated_at=CURRENT_TIMESTAMP
WHERE code='NASDAQ100' AND calendar_code='XNAS' AND data_symbol='NDX';

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'985982483b224803a862c733d894f89c','SPX','S&P 500','us_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','FRED','America/New_York','SP500',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='SPX' OR id=X'985982483b224803a862c733d894f89c');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'2f1cc2d89505497789b56d88e75414df','NASDAQ100','NASDAQ 100','us_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','FRED','America/New_York','NASDAQ100',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='NASDAQ100' OR id=X'2f1cc2d89505497789b56d88e75414df');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'bc7c7d961e0a4254940e73fec0241ad6','XLE','미국 에너지 섹터 ETF (XLE)','sector','etf','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','ARCX','America/New_York','XLE',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='XLE' OR id=X'bc7c7d961e0a4254940e73fec0241ad6');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'6013318a277f4d45804e52716fb2291b','XLF','미국 금융 섹터 ETF (XLF)','sector','etf','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','ARCX','America/New_York','XLF',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='XLF' OR id=X'6013318a277f4d45804e52716fb2291b');

-- 실행 후 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('SPX', 'NASDAQ100', 'XLE', 'XLF');
