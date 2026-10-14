# 뉴스별 예측 후보 시장 대상 조회

`GET /api/v1/news/{newsId}/target-candidates`

Bearer 인증과 UUID newsId가 필요하다. 기존 뉴스 상세 정책과 동일하게 published 뉴스만 조회한다.
뉴스가 없거나 공개되지 않았으면 404 `뉴스를 찾을 수 없습니다.`를 반환한다.
후보가 없으면 200 `{ "candidates": [] }`를 반환한다.

ERD의 `news_target_candidates`와 `market_targets`를 조인하여 sortOrder 오름차순으로 반환한다.
응답 후보 필드는 marketTargetId, code, displayName, marketCategory, targetType,
directionLabels, unit, rationale, sortOrder이다. rationale이 저장되지 않았으면 null이다.
활성 여부에 대한 추가 필터 없이 뉴스에 연결된 후보를 반환한다.
조회는 읽기 전용이며 사용자 예측 대상 선택이나 세션 대상 생성은 수행하지 않는다.

UUID 형식 오류는 400, 인증 실패는 401, 내부 오류는 500으로 처리한다.
오류 응답은 공통 timestamp/status/error/message/path 형식이며
500 메시지는 `서버 내부 오류가 발생했습니다.`이다.
