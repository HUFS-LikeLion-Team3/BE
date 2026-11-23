# 정책 동의 저장

## 동의 목록 조회

`GET /api/v1/users/me/consents`에 `Authorization: Bearer <accessToken>`을 전달합니다.
Body나 Path/Query 파라미터는 없습니다. 로그인한 회원의 전체 동의 기록을 배열로 반환하고,
기록이 없으면 `[]`를 반환합니다. 각 항목은 `id`, `policyType`, `policyVersion`, `consentedAt`을 포함합니다.
동의 시각 오름차순, 동일 시각에서는 ID 오름차순으로 정렬합니다. 시각은 `+09:00`으로 반환합니다.
인증 실패는 401 `인증이 필요합니다.`, 서버 오류는 500 `서버 내부 오류가 발생했습니다.`입니다.

## 동의 저장

`POST /api/v1/users/me/consents`

필수 헤더: `Authorization: Bearer <accessToken>`, `Content-Type: application/json`

```json
{"policyType":"terms_of_service","policyVersion":"1.0"}
```

`policyType`은 `terms_of_service` 또는 `privacy_policy`입니다.
버전은 각각 서버의 `TERMS_VERSION`, `PRIVACY_POLICY_VERSION`에 등록한 현재 버전과 일치해야 합니다.
예시의 `1.0`은 서버에 해당 버전을 설정한 경우에 사용할 수 있습니다.

로그인한 사용자 본인의 명시적 동의를 저장합니다. 동일한 사용자·유형·버전은 기존 ID와 동의 시각을 반환합니다.
신규 가입의 두 필수 동의는 카카오 로그인 과정에서 함께 저장하므로 별도 요청이 필요하지 않습니다.

200 응답 필드: `id`, `policyType`, `policyVersion`, `consentedAt`(한국 시간 `+09:00`).
잘못된 유형·버전·본문은 400 `잘못된 요청입니다.`, 인증 실패는 401 `인증이 필요합니다.`,
서버 오류는 500 `서버 내부 오류가 발생했습니다.`를 공통 오류 형식으로 반환합니다.
