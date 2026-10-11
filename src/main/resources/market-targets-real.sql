-- Real instrument metadata only. Prices are always obtained from the provider.
INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'77777777777747778777777777777777','AAPL','애플','us_equity','stock','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','XNAS','America/New_York','AAPL',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='AAPL' OR id=X'77777777777747778777777777777777');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'88888888888848888888888888888888','KOSPI','코스피 지수','korea_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','XKRX','Asia/Seoul','KS11',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KOSPI' OR id=X'88888888888848888888888888888888');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'99999999999949998999999999999999','US10Y','미국 10년물 국채 수익률 (재무부 일일 기준)','rate','yield','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','%','USTY','America/New_York','BC_10YEAR',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='US10Y' OR id=X'99999999999949998999999999999999');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa','USDKRW','달러/원 환율 (제공처 일일 마감)','fx','exchange_rate','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','KRW','TDFX','Australia/Sydney','USD/KRW',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='USDKRW' OR id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb','GOLD','금 현물 (제공처 일일 마감)','commodity','commodity','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD/troy_oz','TDGD','Australia/Sydney','XAU/USD',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='GOLD' OR id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'cccccccccccc4ccc8ccccccccccccccc','WTI','WTI 원유 현물 (제공처 일일 마감)','commodity','commodity','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD/barrel','TDWT','Australia/Sydney','WTI/USD',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='WTI' OR id=X'cccccccccccc4ccc8ccccccccccccccc');
