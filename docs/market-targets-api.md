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
현재 ERD에는 공통 정규 종가 저장소가 없고 데이터 제공처가 연결되지 않아 기본 상태는
`unavailable`, 사유는 `시장 데이터 제공처가 연결되지 않았습니다.`이다.
제공처 이름·URL과 시장값은 임의로 채우지 않는다.

실제 연동 시 `ReferenceMarketProvider` 빈을 구현한다. 제공처는 대상의 calendarCode/timezone/dataSymbol을
이용해 마지막 확정 정규 종가와 그 직전 정규 종가를 제공해야 한다. 장중값·미확정값은 제외하며
asOfAt에는 수집 시각이 아닌 시장값 기준 시각을 전달한다.
ready 상태에서 날짜·기준 시각·두 종가가 모두 있어야 계산한다. 누락 시 unavailable로 반환한다.
pending/unavailable이면 sessionDate, asOfAt, value, previousCloseValue, changeValue, changePercent는 모두 null이다.
unit, dataSourceName, dataSourceUrl, unavailableReason 필드는 항상 응답에 포함한다.
변화량은 value - previousCloseValue이며 금리의 % 단위 변화량은 퍼센트포인트이다.
변화율은 변화량 / 직전 종가 × 100을 소수 6자리 HALF_UP으로 반올림한다. 직전 종가가 0이면 null이다.
