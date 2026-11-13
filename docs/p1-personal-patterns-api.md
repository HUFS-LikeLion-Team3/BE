# P1 개인 패턴 API — 명세 대비 구현 상태

> 기준: 새 `api 명세 - p1.pdf`(33쪽)의 **12. 개인 패턴**, 22~33쪽. 이 문서는 기존 임시 제안서를 대체한다.
> `/reasons`, `/suggestions`의 상세 계약은 28~33쪽에 추가되었다. 변수 추출·정규화 및 추천 충분성/생성 정책은 문서 자체에서 아직 미확정으로 표시되어 있다.

## 공통 데이터·조회 기준

- 4개 경로 모두 Bearer 인증 필요. 본인 `CurrentUser.id()`만 사용하며 요청에서 user ID를 받지 않는다.
- `learning_sessions.submitted_at`, `predictions.submitted_at`이 존재하고 현재 선택된 `session_targets.is_selected = true`이며 방향값이 있는 예측만 집계한다.
- 한 세션에서 대상이 2개라면 T1/T5/T20 각 예측을 별도 집계한다. `unavailable`을 오답으로 간주하지 않는다.
- **뉴스 분야**는 `learning_sessions.news_id → news.category`이다. `market_targets.market_category`가 아니다.
- 날짜 필터는 **세션의 예측 제출일**(`learning_sessions.submitted_at`) 기준. 시작일 포함, 종료일 포함(`to`의 익일 00:00 미만)이며 기존 서비스의 `Asia/Seoul` 시간대를 사용한다. P1 문서에서 시간대 명시 여부는 별도 확인 필요.

## GET /api/v1/users/me/patterns/categories

- Query: `from` (LocalDate, optional), `to` (LocalDate, optional)
- 200 응답 구조:

```json
{
  "totalSessions": 2,
  "totalPredictions": 6,
  "categories": [
    {
      "category": "rate",
      "categoryName": "금리",
      "sessionCount": 2,
      "predictionCount": 6,
      "directionCounts": {"up": 2, "down": 2, "neutral": 2}
    }
  ]
}
```

- `totalSessions`는 집계에 포함된 학습 세션 ID의 중복 제거 개수.
- 비어 있으면 `totalSessions=0`, `totalPredictions=0`, `categories=[]`.
- 기간 파라미터 형식 오류 또는 `from > to`는 400.
- `categoryName`은 `docs/user-interests.md`에 실제로 명시된 여섯 코드(rate/금리, exchange_rate/환율, semiconductor/반도체, energy/에너지, price/물가, employment/고용)에 한해 한글 이름을 사용한다. 또한 P1 PDF 응답 예시의 `economy/경제`도 사용한다. **뉴스 분야는 이 목록으로 제한하지 않는다.** 나머지 코드는 가짜 표시명을 생성하지 않도록 원래 코드를 임시로 그대로 반환한다. 이 fallback은 팀 합의가 필요하다.

## GET /api/v1/users/me/patterns/horizons

- Query: `from` (LocalDate, optional), `to` (LocalDate, optional), `category` (String, optional)
- `category`는 `news.category`와 정확히 일치하는 것만 추출한다. 뉴스 목록 API의 입력 검증(공백 아님, 최대 255자)을 따른다. 존재하지 않는 코드의 400/빈 결과 정책은 확정되지 않아 현재는 빈 집계 200이다.
- 200 응답 구조:

```json
{
  "totalPredictions": 3,
  "horizons": [
    {"horizon":"T1", "tradingDays":1, "predictionCount":1,
     "directionCounts":{"up":1,"down":0,"neutral":0}},
    {"horizon":"T5", "tradingDays":5, "predictionCount":1,
     "directionCounts":{"up":0,"down":0,"neutral":1}},
    {"horizon":"T20", "tradingDays":20, "predictionCount":1,
     "directionCounts":{"up":0,"down":1,"neutral":0}}
  ]
}
```

- 세 기간은 항상 T1→T5→T20 순서. 데이터가 없는 기간도 `predictionCount=0`.
- 날짜 형식 오류, 역전 또는 빈/255자 초과 분야는 400.
- P1 문서의 `observationCounts`는 **"관측 데이터가 있는 경우 표시할 수 있다"**는 선택적 기술이어서 현 패치에 추가하지 않았다. FE에서 필수인지 확인 후 `market_observations` 데이터와 연계해야 한다. `pending`을 `unavailable`로 세지 않는다.

## GET /api/v1/users/me/patterns/reasons — 명세 계약 적용, 변수 추출 정책 보류

- Query: `from` (LocalDate, 선택), `to` (LocalDate, 선택), `limit` (Integer, 선택; 문서 제안 기본값 10).
- `from > to`, `limit <= 0`, 잘못된 날짜·숫자 형식이면 400.
- 기존 `PatternPredictionRepository`로 **로그인 사용자의 제출된 예측 근거**를 조회한다.
- 추가 `PatternReflectionRepository`로 **로그인 사용자의 최종 회고(`reflection_type=final`, `prompt_key=missed_variable`)**만 조회한다. 원본을 수정하지 않는다.
- `analyzedPredictionCount`: 텍스트가 있는 제출 예측 근거 수. `analyzedReflectionCount`: 텍스트가 있는 해당 최종 회고 응답 수.
- 기간 기준은 기존 프로젝트의 `Asia/Seoul` 기준이며 예측은 **세션 제출일**, 최종 회고는 **회고 작성일**이다. P1 명세에 날짜 기준이 상세히 구분되어 있지 않아 FE/BE 확인이 필요하다.
- P1 명세 28~30쪽은 `consideredVariables`/`missedVariables`의 `variable`, `variableName`, `mentionCount`, `exampleTexts` 구조를 명시하지만 **용어 추출·동의어 정규화 규칙은 별도 확정**을 요구한다. 기존 팀 소스에는 해당 규칙이 없으므로 현재 반환 목록은 **빈 배열**이며, 텍스트를 무작위로 변수라고 가정하거나 AI 결과를 생성하지 않는다. 이 부분은 구현 완료가 아니다.

예시 응답 형태(변수 추출 규칙 확정 전):

```json
{
  "analyzedPredictionCount": 6,
  "analyzedReflectionCount": 2,
  "consideredVariables": [],
  "missedVariables": []
}
```

## GET /api/v1/users/me/patterns/suggestions — 명세 계약 적용, 추천 정책 보류

- Query: `limit` (Integer, 선택; 문서 제안 기본값 5). `limit <= 0`과 잘못된 숫자 형식은 400.
- DTO는 `insufficientData`, `suggestions[].suggestionId/title/description/reason/evidence.type/evidence.count` 형식.
- 이전 구현의 `START_LEARNING`, `EXPLORE_CATEGORY`, `ADD_REASON` 같은 **명세 외 규칙과 필드**는 제거했다.
- P1 명세 31~33쪽에서 **충분한 데이터 기준과 추천 생성 방식은 미확정**이라고 명시한다. 따라서 현재 **개인화 추천을 만들어 반환하지 않으며**, 정책 미확정 동안 `insufficientData=true`, `suggestions=[]`를 반환한다. 이 값은 실제 데이터가 부족하다는 확정 판정이 아니라, 검증된 정책이 없어 추천을 만들 수 없다는 임시 보수적 처리다.
- 정책 확정 전에는 추천 결과를 구현 완료로 표시하거나 PR에서 그렇게 주장하지 말 것.

```json
{
  "insufficientData": true,
  "suggestions": []
}
```

## 팀에 확인할 사항

1. **변수 추출 정책**: 어떤 용어 사전/정규화 코드/동의어·복합어 규칙을 사용할지. 기존 팀 소스에는 확정된 정책이 없음.
2. **제안 충분성·생성 규칙**: 추천을 생성할 최소 기록 수, 적용 가능한 근거 유형, 생성 순서 및 `evidence.count` 계산 기준.
3. **날짜 기준**: `reasons`의 `from/to`를 예측 제출일과 회고 작성일 각각에 적용할지, 하나의 세션 기준일로 통일할지.
4. `observationCounts`와 미정의 뉴스 분야명 처리 방식은 이전 검토 사항 그대로 남아 있음.

## 수정·검증 범위

- `pattern` 내부 Controller/Service/DTO/Test를 수정하고, 최종 회고를 조회하는 `PatternReflectionRepository` 하나만 추가한다.
- P0 인증, 예측, 회고 저장 로직과 DB 스키마는 변경하지 않는다.
- 새 PDF의 규칙에 근거가 없는 자연어 분석 및 투자 추천은 추가하지 않는다.
- 로컬 테스트: `./gradlew test --tests 'com.finsight.pattern.*'` 및 `./gradlew test`.
- 통합 테스트: `reasons?from=2026-01-01&to=2026-12-31&limit=10`, `suggestions?limit=5`, `limit=0`(400), 인증 누락(401).
