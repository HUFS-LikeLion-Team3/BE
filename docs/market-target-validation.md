# 4번 시장 대상 및 6·8번 연결 점검

이 문서는 2026-10-12 당시 기록이다. 이후 추가된 8번 수집 기능의 현재 점검은
[2026-11-03 재점검](market-target-review-2026-11-03.md)을 참고한다.

점검일: 2026-10-12. 현재 체크아웃의 코드·등록 SQL 및 자동 테스트를 점검했다.
운영 중인 MySQL의 실제 행과 API 키로 모든 제공처를 다시 조회한 점검은 아니다.
기존 코드/ID가 있으면 등록 SQL이 덮어쓰지 않으므로 실제 DB 값이 아래 설정과 같은지는 별도 확인해야 한다.

## 시장 대상 설정

| 대상 | dataSymbol | calendarCode | timezone | unit | 기존 실데이터 검증 결과 |
| --- | --- | --- | --- | --- | --- |
| AAPL | AAPL | XNAS | America/New_York | USD | ready |
| US10Y | BC_10YEAR | USTY | America/New_York | % | ready |
| USDKRW | USD/KRW | TDFX | Australia/Sydney | KRW | ready |
| GOLD | XAU/USD | TDGD | Australia/Sydney | USD/troy_oz | ready |
| WTI | WTI/USD | TDWT | Australia/Sydney | USD/barrel | unavailable: 현재 계정 요금제 제한 |
| KOSPI | KS11 | XKRX | Asia/Seoul | point | unavailable: 일정 403, 일봉 심볼 조회 실패 |
| SAMPLE_US / SAMPLE_KR | SAMPLE | SAMPLE | America/New_York / Asia/Seoul | point | 테스트 전용 |

USTY/TDFX/TDGD/TDWT는 내부 조회 분류이며 거래소 MIC가 아니다.
US10Y는 재무부 일일 수익률이고 환율·금·원유는 사용자 승인된 제공처 일일 마감값이다.
거래소 정규 종가와 동일한 의미로 해석하지 않는다. KOSPI는 현재 실제 조회 가능한 설정으로 확정되지 않았다.
일봉의 확정 여부는 기준 시각과 기본 15분 대기 및 데이터 존재로 판단한다. 제공처의 별도 확정 플래그를 검증하는 구조는 아니다.

## referenceMarket와 T0

4번 상세 API는 DB에서 읽은 MarketTarget을 LiveReferenceMarketProvider → MarketDataService로 전달한다.
referenceMarket는 조회 시점에 제공할 수 있는 최신 참고값이다. baseline/관측 테이블에 쓰지 않는다.

현재 8번 OutcomeService는 저장된 baseline/관측을 읽는 서비스다.
프로덕션 코드에서 MarketDataService의 regularCloseOn/regularSessionOn을 호출하는 결과 수집기,
PredictionSubmittedEvent를 받는 AFTER_COMMIT 수신기 또는 T0를 생성·확정하는 구현은 발견되지 않았다.
제출 서비스는 submittedAt을 저장하고 baseline_pending으로 바꾼 뒤 이벤트를 발행한다.
따라서 8번이 4번과 동일한 실제 데이터 기준으로 수집한다거나 T0 규칙을 충족한다는 검증은 아직 불가능하다.
baseline 행이 없는 상태에서 결과를 조회하면 현재 findBaseline이 예외를 발생시킨다.

8번 수집 구현 시 필요한 조건:

- SessionTarget.marketTargetId로 동일한 MarketTarget을 조회한다.
- referenceMarket.value 또는 latestRegularCloses 결과를 제출 시점 T0로 복사하지 않는다.
- 제출 이후 대상 규칙상 적격한 확정 종가를 날짜 순서로 조회하고 최초 확보한 유효 값을 T0로 저장한다.
- expectedBaselineDate, 10세션 기한, 지연 데이터 재조회 및 unavailable 종료를 ERD 규칙에 맞게 구현한다.
- 금리·환율·원자재는 regularSessionOn이 정규 거래소 세션을 반환하지 않는다. 일일 기준값에 대한 수집 일정과
  T1/T5/T20·기한의 세션 계산 규칙을 정규 거래소 캘린더와 구분해 확정해야 한다.
- 동일 ID의 설정이 제출 뒤 변경될 수 있으므로 데이터 기준 유지 정책(설정 버전/스냅샷 등)을 정해야 한다.
  현재 SessionTarget은 marketTargetId만 보관하며 조회 설정 스냅샷은 없다.

## 대상 ID 연결 및 테스트용 대상 구분

뉴스 Candidate.from은 연결된 MarketTarget.id를 marketTargetId로 반환한다.
6번 선택은 요청 ID의 존재와 해당 뉴스의 후보 여부를 확인하고 SessionTarget.marketTargetId에 저장한다.
최종 제출도 후보 ID를 재검증한다. 8번 전체·대상별 결과 DTO는 SessionTarget의 동일 marketTargetId를 반환한다.
sessionTargetId는 세션 안의 선택 레코드 ID이며 marketTargetId와 구분한다.
ID 연결은 코드에서 확인했지만 제출 후 실제 T0 수집까지의 통합 경로는 수집기 미구현으로 검증하지 못했다.

샘플 대상은 SAMPLE 코드·[샘플] 이름·SAMPLE 조회 설정 및 별도 SQL로 구분된다.
다만 isActive=true라 활성 목록에 실대상과 혼재하며, API에 실데이터 지원 여부를 구분하는 별도 필드는 없다.
6번도 활성/실데이터 조회 가능 여부를 따로 검사하지 않는다.
sample-news.sql의 뉴스 후보는 SAMPLE_US/SAMPLE_KR를 가리킨다.
market-targets-real.sql은 실대상을 등록할 뿐 뉴스 추천 후보를 변경하지 않는다.
운영에서는 샘플 대상/후보를 제외하는 데이터 관리가 필요하다.

## 검증

시장 대상·참고값·공통 데이터 서비스·뉴스 후보·6번 선택/제출 관련 자동 테스트를 실행했고 통과했다.
실제 키가 필요한 두 실데이터 테스트는 이번 자동 실행에서 생략됐다.
위 ready/unavailable 표는 이전에 사용자가 공유한 실제 실행 결과를 근거로 한다.
이번 점검은 기능 코드를 수정하지 않고 미완료 사항을 기록했다.
