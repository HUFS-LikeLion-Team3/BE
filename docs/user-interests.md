# 내 관심사 조회

## 관심사 저장 및 수정

`PUT /api/v1/users/me/interests`, `Authorization: Bearer {accessToken}`, `Content-Type: application/json`.

```json
{"interests":[{"interestType":"market","interestKey":"korea"},{"interestType":"topic","interestKey":"semiconductor"}]}
```

200 응답은 위 요청과 동일한 형식이며 id는 포함하지 않는다. 요청 목록으로 현재 사용자 관심사를 트랜잭션 내에서 전체 교체한다. 빈 배열은 모두 해제한다. 사용자 행을 잠가 동시 교체를 직렬화하며 삭제를 flush한 후 삽입해 ERD의 `(user_id, interest_type, interest_key)` 유일 제약을 지킨다. 온보딩 완료 시간은 수정하지 않는다.

시장 허용 키는 `korea`, `us`다. 현재 등록 주제 키는 `semiconductor`, `rate`이며 UserService의 TOPIC_KEYS로 관리한다(ERD에 별도 주제 테이블 없음). 유형·키 누락, 미등록 값, 중복 조합 및 잘못된 JSON은 400 `유효하지 않은 관심사 정보입니다.`를 반환한다. `both`, `all`은 허용하지 않는다. 저장된 시장 키는 추천 조회 시 market_targets의 `korea_equity`, `us_equity`와 매칭한다.

## 조회

`GET /api/v1/users/me/interests`

`Authorization: Bearer {accessToken}` 필수. Path, Query, Body 없음.

저장된 로그인 사용자 관심사만 반환한다. 저장된 항목이 없으면 `{"interests":[]}`이며 국내·미국 전체와 전체 주제를 의미한다. 기본값이나 전체 주제를 의미하는 가상 항목은 추가하지 않는다.

```json
{"interests":[{"id":"uuid","interestType":"market","interestKey":"korea"},{"id":"uuid","interestType":"topic","interestKey":"semiconductor"}]}
```

시장 키는 `korea`, `us`이며 두 시장을 저장한 경우 각각 반환한다. 기존 저장 키 `korea_equity`, `us_equity`는 응답에서 `korea`, `us`로 변환한다. 주제 키는 저장된 값을 반환한다.

인증 실패 시 401과 `로그인이 필요합니다.`, 서버 오류 시 500과 `서버 내부 오류가 발생했습니다.`를 공통 오류 형식(`timestamp`, `status`, `error`, `message`, `path`)으로 반환한다.
