# 활성 시장 대상 조회

`GET /api/v1/market-targets` — `Authorization: Bearer {accessToken}` 필수.

기존 ERD의 `market_targets`에서 `is_active=true`인 대상을 code 오름차순으로 반환한다.
응답은 `{ "targets": [...] }`이며 각 대상은 `id`, `code`, `displayName`,
`marketCategory`, `targetType`, `directionLabels`, `unit`을 포함한다.

선택 쿼리 필터:

| 이름 | 허용 값 |
| --- | --- |
| marketCategory | korea_equity, us_equity, sector, fx, rate, commodity |
| targetType | stock, index, etf, exchange_rate, yield, commodity |

두 필터는 AND로 적용한다. 결과가 없으면 200과 빈 targets 배열을 반환한다.
값은 대소문자를 구분하며 빈 값과 허용하지 않은 값은 400으로 처리한다.
400 메시지: `유효하지 않은 시장 대상 조회 조건입니다.`
500 메시지: `서버 내부 오류가 발생했습니다.`
오류 응답은 공통 timestamp/status/error/message/path 형식을 사용한다.
인증 누락 또는 유효하지 않은 토큰은 기존 인증 정책에 따라 401로 처리한다.

## 시장 대상 상세

`GET /api/v1/market-targets/{marketTargetId}` — Bearer 인증 필수, UUID 경로 변수.
활성 여부와 관계없이 조회하며 목록의 대상 필드에 `isActive`, `referenceMarket`를 추가한다.
대상이 없으면 404 `시장 대상을 찾을 수 없습니다.`를 반환한다. UUID 형식 오류는 400이다.

`referenceMarket`는 제출 전 참고값이며 세션 T0 및 `session_target_baselines`를 생성하거나 변경하지 않는다.
`ReferenceMarketConfiguration`은 구현된 `LiveReferenceMarketProvider`를 빈으로 등록한다.
`LiveReferenceMarketProvider` → 공통 `MarketDataService` → `TwelveDataClient` 경로로
Twelve Data의 `time_series` API에서 실제 일봉 종가를 조회한다. API 키와 일봉 조회 접근 권한,
대상의 실제 dataSymbol/calendarCode/timezone 설정이 필요하다.
설정·권한·지원 데이터가 없으면 `unavailable`이며 unavailableReason은 null이다.
호출 제한·일시적인 장애·아직 도착하지 않은 최신 확정 데이터는 `pending`이다.
market-data 프로필은 AAPL, KOSPI, US10Y, USDKRW, GOLD, WTI를 등록한다. AAPL의 최신·직전 종가를 실제 API로 조회하고
참고값 서비스가 `ready`를 반환하는 연동 테스트를 통과했다. 기존 `SAMPLE_KR`, `SAMPLE_US`는
테스트용 대상이며 실제 시장 데이터가 연결된 대상으로 취급하지 않는다.
미국 주식의 2026 일정은 공식 캘린더를 사용한다.
시장값을 임의로 채우지 않는다. 설정과 공통 조회 방법은 [시장 데이터 연동](market-data.md)을 참고한다.

공통 `MarketDataService`는 대상의 calendarCode/timezone/dataSymbol을 이용해
마지막 확정 정규 종가와 그 직전 정규 종가를 조회한다. 정규장 종료 후 기본 15분의 확정 대기 시간을
적용하고 장중값·미확정값은 제외한다. asOfAt에는 수집 시각이 아닌 해당 정규장의 종료 시각을 전달한다.
시장 결과 수집에서도 같은 서비스의 `regularCloseOn`으로 지정 거래일의 정규 종가를 조회할 수 있다.
US10Y는 `TreasuryYieldClient`의 공식 일일 수익률, USDKRW·GOLD·WTI는 제공처 일일 마감값을 사용한다.
이 네 대상은 사용자 승인에 따라 정규장 종가와 구분한다. `DailyObservationProvider`로 같은 서비스에서
조회하며 실제 게시된 연속 두 관측 중 확정된 값을 제공한다. 세부 기준 시각·단위·권한은 시장 데이터 연동 문서를 참고한다.
ready 상태에서 날짜·기준 시각·두 종가가 모두 있어야 계산한다. 누락 시 unavailable로 반환한다.
pending/unavailable이면 sessionDate, asOfAt, value, previousCloseValue, changeValue, changePercent는 모두 null이다.
unit, dataSourceName, dataSourceUrl, unavailableReason 필드는 항상 응답에 포함한다.
변화량은 value - previousCloseValue이며 금리의 % 단위 변화량은 퍼센트포인트이다.
변화율은 변화량 / 직전 종가 × 100을 소수 6자리 HALF_UP으로 반올림한다. 직전 종가가 0이면 null이다.
