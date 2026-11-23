# 온보딩 완료

`PATCH /api/v1/users/me/onboarding` (기존 POST 요청도 지원)

`Authorization: Bearer {accessToken}` 필수. Path, Query, Body 없음.

```json
{"onboardingCompletedAt":"2026-10-01T18:00:00+09:00"}
```

ERD의 users.onboarding_completed_at에 최초 완료 시각을 저장한다. 사용자 행 잠금과 트랜잭션을 사용하며 반복 요청은 기존 시각을 유지한다. 응답은 한국 시간(+09:00)이다. 관심사 등록 여부는 완료 조건에 포함하지 않는다.

현재 auth.policy.terms-version 및 auth.policy.privacy-version에 해당하는 terms_of_service, privacy_policy 동의가 모두 필요하다. 미동의·이전 버전 동의·정책 버전 미설정은 400 `필수 정책 동의가 필요합니다.`이며 완료 시간을 변경하지 않는다. 반복 요청에도 현재 필수 동의를 확인한다.

인증 실패는 401 `로그인이 필요합니다.`, 사용자 없음은 404 `사용자를 찾을 수 없습니다.`, 서버 오류는 500 `서버 내부 오류가 발생했습니다.`를 공통 오류 형식으로 반환한다.
