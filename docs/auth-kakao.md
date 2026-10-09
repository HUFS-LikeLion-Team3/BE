# 카카오 인증 (P0)

`POST /api/v1/auth/kakao`는 인증 없이 JSON 인가 코드를 받고 서비스 Bearer 토큰을 반환합니다.
세션 쿠키, CSRF 토큰, refresh token API는 사용하지 않습니다. 토큰 유효기간은 기본 1시간입니다.

## 환경 변수

```text
KAKAO_CLIENT_ID=카카오 REST API 키
KAKAO_CLIENT_SECRET=카카오 Client Secret
KAKAO_REDIRECT_URI=http://localhost:3000/auth/kakao/callback
TERMS_VERSION=실제 현재 이용약관 버전
PRIVACY_POLICY_VERSION=실제 현재 개인정보 처리방침 버전
ACCESS_TOKEN_TTL_SECONDS=3600
JWT_SECRET=32바이트_이상_난수키를_Base64로_인코딩한_값
JWT_ISSUER=finsight
```

Redirect URI는 프런트엔드의 인가 코드 수신 주소입니다. 카카오 콘솔 등록값, 인가 코드 요청의
`redirect_uri`, 서버의 `KAKAO_REDIRECT_URI`가 모두 동일해야 합니다.
기존 `/oauth2/authorization/kakao`, `/login/oauth2/code/kakao` 세션 로그인 경로는 사용하지 않습니다.
키는 환경 변수에만 넣고 소스에 저장하지 않습니다.

## 로그인·가입

프런트엔드 로그인 페이지는 현재 이용약관과 개인정보 처리방침의 동의 안내를 보여준 후
카카오 인가를 시작하고, 콜백에서 받은 코드를 아래 API로 전달해야 합니다.
이 API는 해당 안내를 거친 신규 가입 요청으로 처리합니다. JSON에 별도 동의 필드는 없습니다.
백엔드 저장소에는 프런트엔드 로그인 페이지가 포함되어 있지 않습니다.

```http
POST /api/v1/auth/kakao
Content-Type: application/json

{"authorizationCode":"카카오에서 새로 받은 인가 코드"}
```

```json
{
  "accessToken": "서비스에서 발급한 토큰",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "displayName": "사용자",
    "onboardingCompleted": false
  }
}
```

신규 회원은 `users`에 저장하고, `user_consents`에 현재 두 정책의 버전과 같은 동의 시각을
회원 생성과 하나의 트랜잭션으로 저장한 뒤 JWT를 반환합니다. JWT 자체는 DB에 저장하지 않습니다. 정책 버전 미설정 시 신규 가입은 500으로 거부됩니다.
기존 회원 로그인은 현재 DB의 사용자 정보를 반환하며 새로운 정책 동의를 기록하지 않습니다.
동의 유형(`policy_type`)은 `terms_of_service`, `privacy_policy`로 저장하며,
동의 시각은 `consentedAt` 필드를 통해 `consented_at` 컬럼에 저장합니다.
카카오 닉네임이 제공되지 않으면 표시 이름은 `사용자`입니다.

### users 엔티티

`users`는 최종 ERD에 맞춰 `id`, `auth_provider`, `provider_user_id`, `display_name`,
`onboarding_completed_at`, `created_at`, `updated_at`을 사용합니다.
카카오 로그인은 `auth_provider=kakao`, `provider_user_id=카카오 사용자 ID의 문자열`로 회원을 식별합니다.
`auth_provider`와 `provider_user_id` 조합은 유일합니다. REST API 키는 회원 정보에 저장하지 않습니다.
신규 회원의 `onboarding_completed_at`은 NULL이며, 응답의 `onboardingCompleted`는 해당 시각의 존재 여부로 계산합니다.
생성 시 `created_at`과 `updated_at`을 함께 기록하고, 회원 정보 변경 시 `updated_at`을 갱신합니다.

기존 컬럼으로 생성된 로컬 DB는 별도 스키마·데이터 이관이 필요합니다.
`ddl-auto: update`가 컬럼 이름 변경이나 기존 회원 데이터를 자동 이관하지는 않습니다.

## Stateless JWT Access Token

서비스 Access Token은 HS256으로 서명한 stateless JWT입니다. `sub`에 회원 UUID,
`iat`에 발급 시각, `exp`에 만료 시각, `iss`에 발급자를, `jti`에 토큰 식별자를 담습니다. 서명·발급자·만료시간을 검증하며
인증 과정에서 토큰이나 회원을 DB 조회하지 않습니다. `GET /api/v1/auth/me` 등 실제 사용자 데이터 조회는 별도입니다.
토큰 영속 테이블이나 refresh token, 강제 폐기 기능은 추가하지 않습니다.
카카오 액세스 토큰과는 별개입니다. 보호 API에는 `Authorization: Bearer <accessToken>`을 보내세요.
만료·누락·잘못된 토큰은 401을 반환하므로 다시 로그인합니다. 기존 임의 UUID 데이터는 자동 이관되지 않습니다.

`JWT_SECRET`은 카카오 Client Secret과 별개인 서버 서명 키입니다. 코드나 Git에 실제 값을 저장하지 말고
환경 변수로 설정하세요. 누락·잘못된 Base64·32바이트 미만 키는 서버 시작 시 거부됩니다.
같은 환경의 서버는 동일한 키를 사용해야 합니다. 서버가 재시작돼도 같은 키라면 JWT는 만료 전까지 유효합니다.
이전 난수 Access Token은 사용할 수 없으므로 변경 후 다시 로그인해야 합니다.
기존 DB에 만들어진 `access_tokens` 테이블은 이 코드가 사용하거나 새로 생성하지 않으며,
`ddl-auto: update`는 기존 테이블을 자동 삭제하지 않습니다. 실제 DB 삭제는 별도의 스키마 정리 작업입니다.

## Postman 테스트

1. 서버와 MySQL을 실행합니다.
2. 브라우저에서 `https://kauth.kakao.com/oauth/authorize?client_id=본인_REST_API_키&redirect_uri=등록한_URI&response_type=code`로 카카오 인가를 시작합니다. 프런트엔드는 state를 생성하고 콜백에서 검증해야 합니다.
3. 콜백 URL의 `code`를 복사해 Postman의 `POST http://localhost:8080/api/v1/auth/kakao` JSON Body에 넣습니다.
4. 성공 응답의 `accessToken`을 Postman Authorization → Bearer Token에 넣습니다.
5. `GET http://localhost:8080/api/v1/auth/me`를 호출해 같은 사용자 정보를 확인합니다.

인가 코드는 일회용입니다. 재시도할 때는 새 코드를 받으세요.
실제 프런트엔드가 아직 없다면 등록한 콜백 페이지가 열리지 않더라도 주소창의 코드를 복사해 수동 테스트할 수 있습니다.

## 자동 테스트

```powershell
.\gradlew.bat test --tests 'com.finsight.auth.*'
```

H2 DB와 모의 카카오 API로 신규 가입, 재로그인, 정책 동의, 만료 토큰, 요청 검증 및 오류 응답을 검사합니다.
실제 카카오 키를 사용하는 종단 테스트는 별도입니다.
[카카오 공식 REST API 문서](https://developers.kakao.com/docs/ko/kakaologin/rest-api)
