-- 한국은행 ECOS 일일 통계로 KOSPI·KOSDAQ·한국 3년물 국고채 금리를 추가한다.
-- 시세 수집: EcosClient (calendar_code=ECOS, data_symbol=통계표코드/항목코드). 서버에 ECOS_API_KEY가 필요하다.
-- 가격은 넣지 않는다. 같은 code 또는 id가 있으면 INSERT하지 않으므로 반복 실행해도 안전하다.
-- 주의: AI 자료 패키지의 marketSearchTerms에 없는 대상은 AI 피드백 요청이 거절된다. 패키지 갱신과 함께 반영한다.

-- 실행 전 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('KOSPI', 'KOSDAQ', 'KR3Y');

-- 이전 시드(Twelve Data KS11/XKRX, 조회 실패)로 등록된 KOSPI가 있으면 ECOS 경로로 바꾼다.
UPDATE market_targets
SET calendar_code='ECOS', data_symbol='802Y001/0001000', updated_at=CURRENT_TIMESTAMP
WHERE code='KOSPI' AND calendar_code='XKRX' AND data_symbol='KS11';

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'88888888888848888888888888888888','KOSPI','코스피 지수','korea_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','ECOS','Asia/Seoul','802Y001/0001000',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KOSPI' OR id=X'88888888888848888888888888888888');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'dddddddddddd4ddd8ddddddddddddddd','KOSDAQ','코스닥 지수','korea_equity','index','{"up":"상승","down":"하락","neutral":"큰 방향성 없음"}','point','ECOS','Asia/Seoul','802Y001/0089000',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KOSDAQ' OR id=X'dddddddddddd4ddd8ddddddddddddddd');

INSERT INTO market_targets (id,code,display_name,market_category,target_type,direction_labels,unit,calendar_code,timezone,data_symbol,is_active,created_at,updated_at)
SELECT X'eeeeeeeeeeee4eee8eeeeeeeeeeeeeee','KR3Y','한국 3년물 국고채 금리 (한국은행 일일 기준)','rate','yield','{"up":"금리 상승","down":"금리 하락","neutral":"큰 방향성 없음"}','%','ECOS','Asia/Seoul','817Y002/010200000',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM market_targets WHERE code='KR3Y' OR id=X'eeeeeeeeeeee4eee8eeeeeeeeeeeeeee');

-- 실행 후 확인
SELECT code, display_name, calendar_code, data_symbol, unit, is_active
FROM market_targets WHERE code IN ('KOSPI', 'KOSDAQ', 'KR3Y');
