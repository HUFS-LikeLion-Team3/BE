# P1 개인 패턴 API — 명세 대비 구현 상태

> 기준: 새 `api 명세 - p1.pdf`(27쪽)의 **12. 개인 패턴**, 22~27쪽. 이 문서는 기존 임시 제안서를 대체한다.
> `/reasons`, `/suggestions`는 이 PDF에 상세 계약이 없으므로 별도 합의 전까지 **임시 구현**이다.

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

## GET /api/v1/users/me/patterns/reasons — 임시 구현

- P1 PDF에는 상세 요청/응답·분석 규칙이 없다.
- 기존 API의 근거 작성률·기간별 작성률·단어 빈도(`frequentTerms`)를 그대로 유지한다.
- 빈 문자열을 근거 미작성으로 처리하며 한 예측에서 중복되는 단어는 한 번만 센다.
- 형태소 분석이나 AI 기반 '경제 변수 분석'으로 오인하면 안 된다. 팀 검토 필요.

## GET /api/v1/users/me/patterns/suggestions — 임시 구현

- P1 PDF에는 추천 기준·응답 계약이 없다.
- 기존 코드의 `news.category` 건수와 `market_targets.market_category`를 직접 비교한 **서로 다른 분류 체계 혼용 오류**를 수정했다.
- 현재 임시 동작: 기록이 없으면 `START_LEARNING`; 기록이 있으면 **이미 제출한 뉴스 분야 사이에서** 건수가 실제로 적은 분야가 있을 때만 `EXPLORE_CATEGORY`; 이유 없는 예측 기록이 있으면 `ADD_REASON`.
- 예측 기록이 한 분야뿐이거나 분야별 예측 수가 동률이면 부족한 분야를 임의로 추천하지 않는다.
- 이 규칙은 P1 PDF가 확정한 정책이 아니라 **추가 협의가 필요**하다. 투자/시장 성과 추천과 무관하다.

## 수정 범위 및 검증

- `pattern` 패키지의 Controller, Service, Repository, DTO, Test에 국한.
- 기존 Auth/News/Prediction/Outcome 구현, DB 컬럼, 마이그레이션 변경 없음.
- 아래 검증은 **패치를 적용한 로컬 프로젝트에서** 실행해야 한다.

```bash
./gradlew test --tests 'com.finsight.pattern.*'
./gradlew test
```

- 이후 로그인 토큰으로 `categories`, `horizons`의 기본·기간·분야·빈 결과·400/401 응답을 실제 호출해 확인할 것.
