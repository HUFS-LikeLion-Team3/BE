# 시장 데이터 연동

시장 대상 상세는 `ReferenceMarketProvider → LiveReferenceMarketProvider → MarketDataService → MarketDataProvider → TwelveDataClient`
경로로 실제 외부 API를 조회한다. 항상 unavailable을 반환하던 기본 Provider는 제거했다.
DB 테이블을 추가하거나 세션 T0·시장 관측을 저장하지 않는다.
market-data 프로필은 AAPL, KOSPI, US10Y, USDKRW, GOLD, WTI의 실제 대상 메타데이터를 등록한다.
가격은 SQL로 넣지 않는다. 기존 샘플 대상은 별도로 유지한다.

## 설정

Twelve Data API 키를 서버 환경변수 `TWELVE_DATA_API_KEY`에 설정한다.
기본 프로필에서도 읽으므로 추가 프로필은 필수가 아니다.
선택적으로 `market-data` 프로필을 활성화하면 설정값을 YAML에서 확인·조정할 수 있다.

```powershell
$env:TWELVE_DATA_API_KEY = '발급받은 키'
.\gradlew.bat bootRun --args='--spring.profiles.active=market-data'
```

`market.data.twelve-data.api-key` 설정으로도 지정할 수 있다.
API 키는 요청 URL에 넣지 않고 `Authorization: apikey ...` 헤더로 전달한다.
제공처 오류 본문이나 키는 응답에 전달하지 않는다.

| 설정 | 기본값 |
| --- | --- |
| market.data.twelve-data.base-url | https://api.twelvedata.com |
| market.data.confirmation-delay | PT15M |
| market.data.max-lookback-days | 14 (허용 1~31) |
| market.data.us-equity-calendar-mode | official (또는 provider) |

Twelve Data의 HTTP 연결 제한은 3초, 응답 제한은 요청별 5초이다. 거래소 EOD 응답은 60초,
거래 일정은 15분 동안 프로세스 메모리에 캐시한다. 캐시는 각각 최대 1,024개이며 실패는 저장하지 않는다.
재무부 XML은 연결 3초·응답 10초 제한이며 연도별 피드를 60초 동안 최대 3개 캐시한다.
환율·금·원유의 일봉 묶음은 현재 호출마다 조회한다.

## 실제 대상 메타데이터

대상에 제공처가 인식하는 정확한 `dataSymbol`, 거래소 MIC인 `calendarCode`, IANA `timezone`을 등록한다.
예: AAPL / XNAS / America/New_York / stock / USD.
현재 SAMPLE/TEST 대상은 가상 데이터이며 실제 조회 대상으로 바꾸거나 숫자를 추정하지 않는다.
market-data 프로필에서 AAPL이 없으면 `77777777-7777-4777-8777-777777777777`로 등록한다.
같은 코드나 ID가 이미 있으면 덮어쓰지 않는다. 목록 조회에서 실제 저장된 ID를 확인할 수 있다.

거래소 상품은 `MarketDataProvider`, 단일 정규장이 없는 시장은 `DailyObservationProvider`를 공통
`MarketDataService`에서 선택한다. US10Y는 `TreasuryYieldClient`가 재무부 공개 XML의 `BC_10YEAR`를
조회하며 추가 API 키가 필요 없다. ETF나 채권 가격으로 금리를 대체하지 않는다.
환율·금·원유는 `TwelveDataClient`가 제공처의 실제 일봉 마감값을 조회한다.
저장된 unit은 실제 수치의 단위와 일치해야 하며, 가격을 % 수익률로 바꾸는 변환은 수행하지 않는다.

| 코드 | 조회 심볼 / 캘린더 | 단위 | 가격 기준 |
| --- | --- | --- | --- |
| AAPL | AAPL / XNAS | USD | 미국 주식 정규 종가 |
| KOSPI | KS11 / XKRX | point | 코스피 정규 종가 |
| US10Y | BC_10YEAR / USTY | % | 미국 재무부 공식 10년 constant-maturity par yield |
| USDKRW | USD/KRW / TDFX | KRW | 1 USD당 KRW, 제공처 일일 마감 |
| GOLD | XAU/USD / TDGD | USD/troy_oz | 금 현물 트로이온스당 USD, 제공처 일일 마감 |
| WTI | WTI/USD / TDWT | USD/barrel | WTI 현물 배럴당 USD, 제공처 일일 마감 |

USTY/TDFX/TDGD/TDWT는 내부 조회 분류 코드이며 거래소 MIC가 아니다. 외부 API의 mic_code로 보내지 않는다.
금·원유는 현물 기준이며 선물 결제가격·ETF 가격·KRX 금 가격과 구분한다.

## 단일 정규장이 없는 시장의 일일 기준값

사용자 승인에 따라 US10Y는 공식 일일 수익률, 환율·금·원유는 제공처 일일 마감값으로 제공한다.
이 대상들의 `referenceMarket`를 거래소 정규장 종가로 해석하면 안 된다.

- 재무부 수익률은 [공식 산출 기준](https://home.treasury.gov/policy-issues/financing-the-government/interest-rate-statistics)에 따라
  뉴욕 약 15:30 시점의 호가에서 산출한 일일 기준값이다. asOfAt의 15:30은 공식 기준의 대표 시각이며
  개별 체결의 정확한 시각이나 XML 갱신 시각이 아니다.
- 환율·원자재의 일봉 날짜는 [제공처 시간대](https://support.twelvedata.com/en/articles/5745849-timezones) 및
  [원자재 시장 명세](https://twelvedata.com/exchanges/commodity?group=reference)의 Australia/Sydney를 사용한다.
  일봉은 해당 날짜 시작부터 다음 날 00:00까지의 구간으로 해석하며 asOfAt은 구간 종료 경계다.
  응답 메타데이터에 시간대가 없으면 위 세 심볼에 대해 공식 문서의 기본 시간대를 적용한다.
  다른 시간대가 명시되면 unavailable로 처리한다. 환율 응답은 exchange_timezone 필드를 생략할 수 있다.
- 최신·직전 값은 lookback 범위 내 실제 제공된 연속 관측 두 개다. 주말·휴일에 값이나 거래일을 만들지 않는다.
  최신 조회에 사용할 확정 두 날짜의 중복을 검증한다. 같은 날짜·수치·기준 시각은 하나로 합치고,
  다른 수치나 기준 시각이 있으면 임의로 선택하지 않고 unavailable로 처리한다.
  더 오래된 날짜의 충돌은 최신 두 날짜의 조회를 막지 않는다. 지정 날짜 조회는 요청 날짜의 모든 관측을 검증한다.
  미완성 일봉은 제외하고, 두 확정 관측을 확보하지 못하면 pending이다.
- 이미 게시된 과거 공식 값이 최신으로 남을 수 있다. 마지막 게시 날짜를 sessionDate로 그대로 반환한다.
  이는 거래소 캘린더와 비교해 최신 거래일 누락을 pending으로 처리하는 정규 종가 경로와 다르다.

## 확정 종가 규칙

- XNAS/XNYS의 2026 날짜는 [Nasdaq 공식 일정](https://www.nasdaqtrader.com/Trader.aspx?id=Calendar)과 [NYSE 공식 일정](https://www.nyse.com/trade/hours-calendars)에 따른 캘린더를 사용한다. 주말·휴장일·13시 조기 폐장을 구분한다.
- 공식 캘린더에 없는 시장·연도와 provider 모드는 `/exchange_schedule`에 실제 날짜와 MIC를 전달한다. 지원하지 않는 일정을 임의로 생성하지 않는다.
- `/time_series`에 interval=1day, adjust=none, prepost=false를 전달한다. 과거 조회에는 정확한 start_date/end_date를 전달하고 응답 날짜·심볼·MIC·interval을 다시 검증한다.
- 현재 거래소 날짜에서 가장 최근 종료한 세션을 찾는다. 종료 후 confirmation-delay 이전에는 미확정이다.
- 마지막 종료 세션의 데이터가 아직 없으면 pending이다. 이전 값으로 최신 날짜를 채우지 않는다.
- 직전 세션은 날짜별 거래 일정을 역순 조회해 구한다. 휴장일은 제외하고 거래일의 종가가 없으면 더 오래된 값으로 대체하지 않는다.
- asOfAt은 해당 날짜 정규장의 종료 시각과 실제 시간대 오프셋이다. 일봉 시작 시각·수집 시각·현재 시각을 사용하지 않는다.
- ready일 때만 종가와 변화량을 반환한다. pending/unavailable은 숫자·시장 날짜·시각이 null이며 unavailableReason도 null이다.

`confirmation-delay`는 제공처 게시 지연을 위한 추가 대기 시간이다. 시간이 지났다는 이유만으로 값을 생성하지 않으며,
실제 일봉 종가 응답이 있어야 한다. 유효한 종료 세션을 lookback 범위 안에서 확인할 수 없으면 unavailable이다.
공식 캘린더 버전은 2026이다. 새 연도의 공식 일정과 임시 휴장 공지가 있으면 캘린더를 업데이트해야 한다.

## 8번 결과 수집에서 재사용

`MarketDataService.regularCloseOn(target, sessionDate)`는 지정 거래일의 확정 종가만 반환한다.
`Result<Close>`의 status를 보고 호출자가 저장·재시도·기한 종료 여부를 결정한다.
`Close`에는 sessionDate, asOfAt, value, unit, dataSourceName, dataSourceUrl이 포함된다.

`regularSessionOn(target, sessionDate)`는 거래일 여부와 실제 개장·종료 시간을 반환한다.
단일 정규장이 없는 위 네 대상은 이 메서드로 거래소 세션을 만들지 않는다. 결과 수집에서는
`regularCloseOn`으로 지정 날짜의 공식 기준값/일봉 마감값을 조회하고, 관측이 없는 날짜는 unavailable로 처리한다.
조회 장애는 `MarketDataProvider.DataException`으로 구분한다. 휴장과 거래 일정 조회 실패를 같은 것으로 취급하지 않는다.
T0/T1/T5/T20 계산·10세션 기한·DB 상태 전환은 결과 수집 트랜잭션에서 처리해야 한다.
`latestRegularCloses`는 제출 전 화면 전용이며, 이미 제출한 세션의 T0를 이 값으로 교체하지 않는다.

## 제공처 권한 및 검증

[Twelve Data 공식 문서](https://twelvedata.com/docs)의 `/time_series`, `/exchange_schedule` 계약을 사용한다.
미국 주식 2026 공식 캘린더 경로는 유료 거래 일정 API를 호출하지 않는다.
KOSPI를 포함한 다른 거래소 시장·연도 또는 provider 모드에서는 거래 일정 endpoint의 Ultra/Enterprise 권한과 요청당 100 credits가 필요하다.
KOSPI 지수·금·원유 자체의 일봉 접근 권한도 별도 확인해야 한다. 대상 등록은 계정 권한을 부여하지 않는다.
US10Y와 환율·금·원유의 일일 기준값 경로에서는 유료 거래 일정 API를 호출하지 않는다.
일봉 심볼에 대한 계정 권한이 없거나 사용한 endpoint가 403이면 unavailable로 반환한다.
일시 오류·429·5xx는 pending, 인증/권한/미지원 심볼·데이터 누락은 unavailable이다.

```powershell
.\gradlew.bat test --tests 'com.finsight.market.*'
```

HTTP 응답을 재현한 테스트로 실제 어댑터부터 상세 JSON의 ready 응답까지 검증한다.
주말·휴장·조기 폐장·DST·미확정·직전값 누락·HTTP 장애·키 누락도 검증한다.
실제 계정의 API 키가 없으면 외부 실데이터 수신과 계정 권한은 별도로 확인해야 한다.

## 실제 키로 끝까지 검증하고 서버 실행

키와 DB 환경변수가 있는 PowerShell에서 기존 서버를 종료한 뒤 실행한다.

```powershell
.\scripts\Test-LiveMarketData.ps1 -StartServer
```

스크립트는 제공처 원본 일봉을 조회하고, 실제 Java 클라이언트와 FinSight 참고값 서비스로 실데이터를 검증한다.
모의 HTTP·고정 종가·테스트 DB는 사용하지 않는다. `build/reports/market-data-live.json`에 결과가 저장된다.
실데이터 검증에 성공한 경우에만 서버를 실행한다. 키를 출력하거나 파일에 저장하지 않는다.
`-StartServer`를 생략하면 데이터 검증만 한다. 일반 자동 테스트에서는 실데이터 테스트가 기본적으로 생략된다.

서버 시작 후 FinSight 로그인 Bearer 토큰으로 다음을 호출한다.

```http
GET /api/v1/market-targets/77777777-7777-4777-8777-777777777777
```

실제 애플 종가의 unit은 USD이다. 시장 참고값과 changeValue는 달러, changePercent는 %이다.
참고값 조회는 사용자 세션의 T0를 만들거나 변경하지 않는다.

## 추가 다섯 시장의 실데이터 검증

API 키가 설정된 PowerShell에서 실행한다. 각 대상의 ready/pending/unavailable과 실제 가격·기준 시각을
`build/reports/markets-live.json`에 기록한다. 일부 대상의 권한 부족도 나머지 결과와 함께 표시하며,
일반 자동 테스트에서는 이 실데이터 테스트를 생략한다.

```powershell
.\scripts\Test-LiveMarkets.ps1
# 키 없이 재무부 공식 수익률만 검증
.\scripts\Test-LiveMarkets.ps1 -Targets US10Y
# 전 대상 검증 성공 후 서버 실행 (기존 서버를 먼저 종료)
.\scripts\Test-LiveMarkets.ps1 -StartServer
```

전 대상이 ready인지 확인한 경우에만 해당 계정에서 전체 실데이터 검증이 성공했다고 판단한다.
market-data 프로필로 서버를 재시작하면 신규 대상이 등록된다. 목록 API에서 실제 저장된 대상 ID를 확인한 뒤
상세 API를 호출한다. 기존 코드/ID는 덮어쓰지 않으므로 기존 대상의 잘못된 메타데이터는 별도 수정이 필요하다.
