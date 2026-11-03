# 시장 대상 4·6·8번 재점검 (2026-11-03)

## 점검 후 보완

아래 1·2번의 코드 문제를 수정했다. TDFX/TDGD/TDWT는 주말을 T0 후보에서 제외하지 않으며,
관련 예정일·기한 추정도 달력일로 계산한다. 저장 서비스는 제출 시각과 같은 시각도 거부한다.
제출 경계와 실제 저장 경로를 검증하는 회귀 테스트를 추가했다.
market-data YAML에 OUTCOME_COLLECTION_ENABLED(기본 false)를 명시했고 운영 시작 방법을 market-data.md에 기록했다.
샘플 비활성화 SQL을 제공하되 운영 DB에는 실행하지 않았다. 기존 뉴스 후보·세션 참조도 변경하지 않았다.
아래 발견 사항은 보완 이전 점검 기록이며, 자동 수집 활성화와 운영용 뉴스 후보 구성은 운영 시 적용해야 한다.

현재 체크아웃 feat/news의 코드·등록 SQL·자동 테스트 기준이다. 운영 DB 행과 실제 계정 권한을
이번에 재조회하지 않았다. ready/unavailable의 과거 실데이터 결과는 현재 권한을 보증하지 않는다.

## 확인된 연결

- 4번은 MarketTargetRepository의 엔티티를 LiveReferenceMarketProvider → MarketDataService.latestRegularCloses에 전달한다.
- 8번 OutcomeCollectionService는 SessionTarget.marketTargetId로 동일 MarketTargetRepository에서 읽고
  MarketDataService.regularCloseOn(target, date)로 조회한다. 제공처·단위·시간대 기준은 공통 서비스 경로다.
- 뉴스 후보는 MarketTarget.id를 반환한다. 6번은 존재·해당 뉴스 후보 여부를 검증해 동일 marketTargetId를 저장한다.
  8번 DTO도 해당 ID를 반환한다. sessionTargetId는 별도 선택 레코드 ID다.
- referenceMarket는 읽기 전용이며 baseline을 만들거나 변경하지 않는다. 8번은 별도 baseline에 날짜 지정 종가를 저장한다.
- 거래소 T0 예정일은 정규장 종료 시각이 submittedAt보다 늦은 최초 세션으로 계산한다.
  장중 제출은 당일 종가가 대상이 될 수 있으며, 마감 시각과 같은 시점에 제출하면 다음 세션이다.
  OutcomeBaselineCoordinator는 예정일부터 날짜 순서로 조회한다. 참고값을 T0로 복사하는 경로는 발견되지 않았다.
- 이전 점검과 달리 현재는 거래소·일일 기준값 수집 스케줄러와 저장 서비스가 구현되어 있다.

## 등록 설정

| 코드 | dataSymbol | calendarCode | timezone | unit | 판정 |
| --- | --- | --- | --- | --- | --- |
| AAPL | AAPL | XNAS | America/New_York | USD | 기존 실데이터 검증 성공 |
| US10Y | BC_10YEAR | USTY | America/New_York | % | 재무부 공식 일일 수익률. 기존 실데이터 검증 성공 |
| USDKRW | USD/KRW | TDFX | Australia/Sydney | KRW | 제공처 일일 마감값. 기존 실데이터 검증 성공 |
| GOLD | XAU/USD | TDGD | Australia/Sydney | USD/troy_oz | 금 현물 일일 마감값. 기존 실데이터 검증 성공 |
| WTI | WTI/USD | TDWT | Australia/Sydney | USD/barrel | 기존 계정의 Grow/Venture 미충족으로 조회 제한 |
| KOSPI | KS11 | XKRX | Asia/Seoul | point | 기존 일정 403·일봉 심볼 오류. 조회 적합성을 확정할 수 없음 |
| SAMPLE_US / SAMPLE_KR | SAMPLE | SAMPLE | America/New_York / Asia/Seoul | point | 테스트 전용 |

USTY/TDFX/TDGD/TDWT는 내부 분류 코드로, 거래소 MIC가 아니다. 네 대상은 사용자가 승인한 공식 수익률/제공처
일일 마감값 기준이며 정규 거래소 종가로 표현하면 안 된다. 등록 SQL은 기존 코드/ID가 있으면 덮어쓰지 않는다.
따라서 실제 DB 설정이 표와 다를 가능성은 이번 코드 점검만으로 배제하지 못한다.

## 수정 또는 운영 확인이 필요한 항목

### 1. 일일 시장의 최초 T0 날짜에서 주말을 무조건 제외

DailyOutcomeScheduler.firstDate(508행)는 모든 일일 시장에 isWeekday를 적용한다.
반면 4번의 공통 서비스는 실제 제공된 주말 일봉도 포함한다. 이전 실제 검증에서도 금·환율의
10월 10일·11일 값이 반환됐다. 토요일 시드니 시간에 제출하면 실제 첫 적격 값은 토요일 마감일 수 있지만,
8번은 시작일을 월요일로 밀어 최초 확정값을 놓칠 수 있다. USTY와 환율·원자재의 관측일 정책을 분리해야 한다.
미리 계산하는 기한도 addWeekdays를 쓰므로 실제 게시 관측 기준과의 일치 여부를 함께 검증해야 한다.

### 2. 저장 단계의 엄격한 제출 이후 조건 누락

OutcomeCollectionService.collectBaseline(158행)은 close.asOfAt().isBefore(submittedAt)만 거부한다.
따라서 같은 시각은 허용한다. 정상 거래소 일정 계산은 엄격히 이후를 선택하지만, 저장 서비스 자체는
독립적으로 이 조건을 보장하지 않는다. !close.asOfAt().isAfter(submittedAt)으로 같은 시각도 거부하는 것이 요구사항에 맞다.

### 3. 조회 가능·샘플 대상의 운영상 분리가 없음

sample-news.sql은 샘플 지수를 isActive=true로 등록하고 샘플 뉴스 추천에 연결한다.
활성 목록은 샘플·코스피·WTI를 별도 구분 필드 없이 반환한다. 코드/이름으로는 식별되지만
isActive가 실제 조회 가능함을 뜻하지는 않는다. 6번은 후보 여부만 확인하므로 추천된 샘플도 선택 가능하다.
실대상 등록만으로 샘플 뉴스 후보가 실제 대상 ID로 변경되지는 않는다. 운영용 후보 데이터 관리가 필요하다.

### 4. 자동 수집 활성화 설정 확인 필요

수집 스케줄러와 EnableScheduling은 finsight.outcome.collection.enabled=true일 때만 활성화된다.
현재 저장소 application YAML에는 해당 설정이 없다. 실행 환경변수/외부 설정이 없으면
4번 referenceMarket는 ready여도 8번 자동 수집은 실행되지 않는다.

## 추가 한계

8번도 동일 marketTargetId의 현재 조회 설정을 매번 읽는다. SessionTarget에 제공처 설정 스냅샷이 없으므로
제출 뒤 dataSymbol/calendarCode/timezone이 바뀌면 수집 기준이 바뀔 수 있다. unit 불일치 방어는 있지만
같은 단위의 심볼 변경까지 막는 구조는 아니다.

API 키가 필요한 실데이터 테스트는 이번 점검에서 실행하지 않았다. 자동 테스트 통과만으로
위 경계 조건과 운영 설정의 문제가 해결됐다고 판단해서는 안 된다. 이번 작업은 코드 변경 없이 점검 결과를 기록했다.
