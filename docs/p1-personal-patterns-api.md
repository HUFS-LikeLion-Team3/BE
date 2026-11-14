# P1 개인 패턴 API — 명세 대비 구현 상태

> 기준: 새 `api 명세 - p1.pdf`(33쪽)의 **12. 개인 패턴**, 22~33쪽. 이 문서는 기존 임시 제안서를 대체한다.
> `/reasons`, `/suggestions`의 상세 계약은 28~33쪽에 추가되었다. 이후 FE/BE 합의에 따라 **사전 기반 변수 추출, 뉴스 단위 중복 제거, 데이터 충분성 및 추천 생성 규칙**을 P1 구현에 반영했다. 본 문서는 현재 코드 기준의 동작을 설명한다.

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

## GET /api/v1/users/me/patterns/reasons — 사전 기반 변수 분석

- Query: `from` (LocalDate, 선택), `to` (LocalDate, 선택), `limit` (Integer, 선택; 기본값 **10**).
- `from > to`, `limit <= 0`, 잘못된 날짜·숫자 형식이면 **400**.
- 로그인 사용자의 **제출된 예측 중 판단 이유(`reason`)가 있는 기록**과 **완료된 학습 세션의 최종 회고(`reflection_type=final`, `prompt_key=missed_variable`)**를 읽기 전용으로 조회한다. 원본 기록은 변경하지 않는다.
- `analyzedPredictionCount`: 판단 이유가 비어 있지 않은 **예측 기록 건수**. `analyzedReflectionCount`: 본문이 비어 있지 않은 해당 **최종 회고 기록 건수**. 이 두 건수는 중복 제거된 뉴스 수와 다르다.
- **분석 활성화 기준**: 판단 이유가 있는 제출 예측의 **서로 다른 `newsId`가 5개 이상**. 5개 미만이면 위 분석 건수는 반환하되 `consideredVariables=[]`, `missedVariables=[]`로 반환한다. T1/T5/T20 등 동일 뉴스의 여러 예측은 뉴스 수를 늘리지 않는다.
- 변수 추출은 다음 **최소 문자열 사전**을 사용한다. 대소문자·띄어쓰기·문맥을 해석하는 AI 의미 분석은 수행하지 않는다.

  | `variable` 코드 | `variableName` | 현재 탐지 문자열 |
  | --- | --- | --- |
  | `interest_rate` | 금리 | `기준금리`, `금리 인상`, `금리` |
  | `exchange_rate` | 환율 | `환율` |
  | `prices` | 물가 | `물가` |
  | `employment` | 고용 | `고용` |
  | `earnings` | 실적 | `실적` |
  | `supply_demand` | 수급 | `수급` |
  | `commodities` | 원자재 | `원자재` |

- 한 문장에 위 문자열이 포함되면 해당 변수가 **언급되었다**고 판단한다. `금리`, `기준금리`, `금리 인상`은 모두 `interest_rate`로 정규화한다. 상승·하락 방향, 부정 표현의 의미, 예측의 정오 여부는 추론하지 않는다.
- `consideredVariables`: 예측 판단 이유에서 언급된 변수. `missedVariables`: 최종 회고의 **`missed_variable`** 응답에서 언급된 변수. 두 출처를 **독립 집계**하며 회고에서 언급했다고 최초 예측에서 고려한 것으로 간주하지 않는다. 예측 근거에 없다는 이유만으로 놓친 변수로 판정하지도 않는다.
- **`mentionCount`는 해당 변수가 언급된 서로 다른 뉴스 수**이다. 같은 뉴스의 여러 예측 또는 동일 문장 내 반복 언급은 해당 변수에 대해 **뉴스당 1회**로 집계한다. 예측과 회고는 각각의 배열 내에서 별도로 집계한다.
- 변수는 `mentionCount` **내림차순**, 동률 시 `variable` 코드 오름차순 정렬한다. `limit`는 **`consideredVariables`와 `missedVariables`에 각각** 적용한다.
- `exampleTexts`는 실제 작성된 원문을 변경 없이 사용한다. 같은 뉴스에서 해당 변수의 원문 예시는 하나만 수집하고, **변수당 최대 3개**를 반환한다.
- 조회 범위의 날짜 기준은 기존 구현대로 예측 근거는 **세션 제출일**(`learning_sessions.submitted_at`), 최종 회고는 **회고 작성일**(`reflection_notes.created_at`)이며 `Asia/Seoul`의 시작일/종료일을 포함한다. 양쪽 날짜를 동일 기준으로 통일할지는 별도 협의 사항이다.
- 추출된 변수가 없으면 분석 조건을 충족하더라도 변수 배열은 빈 배열이다. 이 응답은 성과 평가나 투자 조언이 아니다.

5개 이상의 서로 다른 뉴스가 존재하는 계정의 예시 응답(문장은 설명용 예시이며 실서비스는 실제 저장 원문을 반환):

```json
{
  "analyzedPredictionCount": 6,
  "analyzedReflectionCount": 2,
  "consideredVariables": [
    {
      "variable": "interest_rate",
      "variableName": "금리",
      "mentionCount": 3,
      "exampleTexts": ["금리 동결이 시장에 긍정적인 영향을 줄 것으로 판단했습니다."]
    }
  ],
  "missedVariables": [
    {
      "variable": "exchange_rate",
      "variableName": "환율",
      "mentionCount": 2,
      "exampleTexts": ["환율 변동을 충분히 고려하지 못했습니다."]
    }
  ]
}
```

서로 다른 뉴스가 **5개 미만**인 경우(예측/회고 건수는 그대로 표시):

```json
{
  "analyzedPredictionCount": 6,
  "analyzedReflectionCount": 1,
  "consideredVariables": [],
  "missedVariables": []
}
```

## GET /api/v1/users/me/patterns/suggestions — 규칙 기반 학습 제안

- Query: `limit` (Integer, 선택; 기본값 **5**). `limit <= 0`, 잘못된 숫자 형식이면 **400**.
- 로그인 사용자의 **판단 이유가 있는 제출 예측에 연결된 서로 다른 뉴스가 5개 이상**이면 제안 생성을 검토한다. 뉴스 수가 5개 미만이면 **`insufficientData=true`, `suggestions=[]`**이다. 이 값은 실제 데이터 충분성 기준에 따른 판정이다.
- `reasons`와 동일한 최소 변수 사전, `newsId` 기준 중복 제거, 예측·최종 회고 분리 집계를 사용한다. 상승·하락 방향이나 예측 성과를 추론하지 않는다.
- **제안 규칙 1 — 고려 변수 반복**: 예측 판단 이유에 동일 변수가 **서로 다른 뉴스 3개 이상**에서 등장하면 해당 변수 외 **다른 관점도 함께 확인**하도록 제안한다. `suggestionId`는 `consider-other-perspectives-{variable-code-with-hyphens}`, `evidence.type`은 **`considered_variable`**.
- **제안 규칙 2 — 회고에서 놓친 변수 반복**: 완료된 세션의 최종 `missed_variable` 회고에 동일 변수가 **서로 다른 뉴스 2개 이상**에서 등장하면 다음 학습에서 해당 변수를 확인하도록 제안한다. `suggestionId`는 `consider-{variable-code-with-hyphens}`, `evidence.type`은 **`missed_variable`**.
- **미언급만으로 놓친 변수로 판단하지 않는다.** `missed_variable` 유형의 제안은 사용자가 실제로 작성한 최종 회고 내용에 근거해야 한다.
- 모든 제안의 `evidence`에는 `type`(출처 유형), `count`(**해당 변수가 언급된 서로 다른 뉴스 수**), `exampleTexts`(**실제 사용자 원문 사례, 최대 3개**)를 반환한다. 예측 근거와 회고의 증거는 합쳐 세지 않는다.
- 제안은 `evidence.count` **내림차순**, 동률 시 `suggestionId` 오름차순 정렬한다. `limit`는 **전체 제안 배열**에 적용한다.
- **데이터가 충분하더라도** 두 제안 조건에 맞는 변수가 없으면 **`insufficientData=false`, `suggestions=[]`**로 반환한다. 근거가 없는 추천은 만들지 않는다.
- 우선 **규칙 기반** 학습 방향 제안만 수행하며 별도의 AI 의미 분석, 근거 없는 성과 분석, 투자 매수·매도 추천은 하지 않는다.
- 이전 구현의 `START_LEARNING`, `EXPLORE_CATEGORY`, `ADD_REASON` 같은 **명세 외 임시 제안**은 사용하지 않는다. 기존 예측·회고 원문이나 학습 세션을 수정·생성하지 않는다.

5개 이상의 서로 다른 뉴스가 있고 두 제안 규칙을 모두 충족한 경우의 예시(문장은 설명용 예시):

```json
{
  "insufficientData": false,
  "suggestions": [
    {
      "suggestionId": "consider-other-perspectives-interest-rate",
      "title": "금리 외의 변수도 함께 확인해 보세요.",
      "description": "여러 뉴스에서 금리를 반복적으로 고려했습니다. 다음 학습에서는 다른 경제 변수도 함께 검토해 보세요.",
      "reason": "서로 다른 뉴스 3개에서 금리 변수를 예측 근거로 언급함",
      "evidence": {
        "type": "considered_variable",
        "count": 3,
        "exampleTexts": ["금리 동결이 시장에 긍정적인 영향을 줄 것으로 판단했습니다."]
      }
    },
    {
      "suggestionId": "consider-exchange-rate",
      "title": "환율 변수를 함께 고려해 보세요.",
      "description": "최종 회고에서 환율을 놓쳤다고 기록한 사례가 반복되었습니다. 다음 학습에서는 해당 변수를 함께 확인해 보세요.",
      "reason": "서로 다른 뉴스 2개의 최종 회고에서 환율을 놓친 변수로 언급함",
      "evidence": {
        "type": "missed_variable",
        "count": 2,
        "exampleTexts": ["환율 변동을 충분히 고려하지 못했습니다."]
      }
    }
  ]
}
```

**데이터 부족 시 — 200 OK**

```json
{"insufficientData": true, "suggestions": []}
```

**데이터는 충분하지만 제안 조건 미충족 시 — 200 OK**

```json
{"insufficientData": false, "suggestions": []}
```

## 추가 협의 사항

1. **변수 사전 확장 및 코드 계약**: 현재 코드에 있는 최소 7개 변수의 정규화 코드(`prices`, `supply_demand`, `commodities` 등)와 향후 추가 동의어는 FE/BE가 함께 확정한다. 이 문서는 **현재 구현된** 문자열만 기술한다.
2. **날짜 기준**: `reasons`의 `from/to`는 예측 근거에 세션 제출일, 회고에 회고 작성일을 각각 적용 중이다. 하나의 세션 기준일로 통일할지는 별도 확인한다.
3. `horizons.observationCounts`와 미정의 뉴스 분야의 `categoryName` fallback은 기존 검토 사항 그대로 남아 있다.
4. 변수 사전은 단순 **문자열 포함 여부**만 검사하므로 복합 표현, 동음이의, 문맥·부정 의미 판별 등은 이후 분석 범위를 확정해야 한다.

## 수정·검증 범위

- `pattern` 패키지 안의 Controller/Service/DTO/Repository/Test 변경으로 구현한다. 기존 P0 인증, 예측·회고 저장 로직 및 DB 스키마는 변경하지 않는다.
- 변수 분석 및 추천 정책은 **합의된 뉴스 단위 규칙**에 맞춰 구현했다. 데이터 조회에 실패하면 무조건 빈 배열을 돌려주는 임시 정책은 더 이상 사용하지 않는다.
- 로컬 테스트: `./gradlew test --tests 'com.finsight.pattern.*'` 및 `./gradlew test` **사용자 환경에서 모두 통과(2026-11-14)**.
- 실제 API 호출(동일 카카오 테스트 계정): `reasons`는 예측 6건, 최종 회고 1건 및 빈 변수 배열로 HTTP 200; `suggestions`는 `insufficientData=true`, 빈 제안으로 HTTP 200. **이 계정은 5개 뉴스 조건을 충족하지 않아** 데이터 부족 경로만 통합 확인했다.
- 5개 이상 서로 다른 뉴스에서 변수 추출과 실제 제안이 생성되는 **실DB 통합 시나리오는 아직 미검증**이고, 관련 규칙은 단위 테스트로 확인했다.
