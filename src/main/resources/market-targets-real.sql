-- Real instrument metadata only. Prices are always obtained from the provider.
INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'77777777777747778777777777777777','AAPL','애플','us_equity','stock','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD','XNAS','America/New_York','AAPL',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='AAPL' OR id=X'77777777777747778777777777777777');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'88888888888848888888888888888888','KOSPI','코스피 지수','korea_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','ECOS','Asia/Seoul','802Y001/0001000',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KOSPI' OR id=X'88888888888848888888888888888888');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'99999999999949998999999999999999','US10Y','미국 10년물 국채 수익률 (재무부 일일 기준)','rate','yield','{"up":"금리 상승","down":"금리 하락","neutral":"큰 방향성 없음"}','%','USTY','America/New_York','BC_10YEAR',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='US10Y' OR id=X'99999999999949998999999999999999');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa','USDKRW','달러/원 환율 (제공처 일일 마감)','fx','exchange_rate','{"up":"환율 상승(원화 약세)","down":"환율 하락(원화 강세)","neutral":"큰 방향성 없음"}','KRW','TDFX','Australia/Sydney','USD/KRW',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='USDKRW' OR id=X'aaaaaaaaaaaa4aaa8aaaaaaaaaaaaaaa');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb','GOLD','금 현물 (제공처 일일 마감)','commodity','commodity','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD/troy_oz','TDGD','Australia/Sydney','XAU/USD',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='GOLD' OR id=X'bbbbbbbbbbbb4bbb8bbbbbbbbbbbbbbb');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'cccccccccccc4ccc8ccccccccccccccc','WTI','WTI 원유 현물 (EIA 일일 기준)','commodity','commodity','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','USD/barrel','TDWT','Australia/Sydney','WTI/USD',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='WTI' OR id=X'cccccccccccc4ccc8ccccccccccccccc');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'dddddddddddd4ddd8ddddddddddddddd','KOSDAQ','코스닥 지수','korea_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','ECOS','Asia/Seoul','802Y001/0089000',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KOSDAQ' OR id=X'dddddddddddd4ddd8ddddddddddddddd');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'eeeeeeeeeeee4eee8eeeeeeeeeeeeeee','KR3Y','한국 3년물 국고채 금리 (한국은행 일일 기준)','rate','yield','{"up":"금리 상승","down":"금리 하락","neutral":"큰 방향성 없음"}','%','ECOS','Asia/Seoul','817Y002/010200000',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KR3Y' OR id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeeee');

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
