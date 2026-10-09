# FinSight Backend

FinSight 백엔드 프로젝트입니다.

## 카카오 로그인 (P0)

`POST /api/v1/auth/kakao`는 인증 없이 JSON 인가 코드를 받고 서비스 Bearer 토큰을 반환합니다.
세션 쿠키, CSRF 토큰, refresh token API는 사용하지 않습니다. 토큰 유효기간은 기본 1시간입니다.

### 환경 변수

```text
KAKAO_CLIENT_ID=카카오 REST API 키
KAKAO_CLIENT_SECRET=카카오 Client Secret
KAKAO_REDIRECT_URI=http://localhost:3000/auth/kakao/callback
TERMS_VERSION=실제 현재 이용약관 버전
PRIVACY_POLICY_VERSION=실제 현재 개인정보 처리방침 버전
ACCESS_TOKEN_TTL_SECONDS=3600
```

Redirect URI는 프런트엔드의 인가 코드 수신 주소입니다. 카카오 콘솔 등록값, 인가 코드 요청의
`redirect_uri`, 서버의 `KAKAO_REDIRECT_URI`가 모두 동일해야 합니다.
기존 `/oauth2/authorization/kakao`, `/login/oauth2/code/kakao` 세션 로그인 경로는 사용하지 않습니다.
키는 환경 변수에만 넣고 소스에 저장하지 않습니다.

### 로그인·가입

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
회원 생성·토큰 발급과 하나의 트랜잭션으로 저장합니다. 정책 버전 미설정 시 신규 가입은 500으로 거부됩니다.
기존 회원 로그인은 현재 DB의 사용자 정보를 반환하며 새로운 정책 동의를 기록하지 않습니다.
카카오 닉네임이 제공되지 않으면 표시 이름은 `사용자`입니다.

토큰은 256비트 난수이며 `access_tokens`에는 SHA-256 해시와 만료 시각만 저장합니다.
카카오 액세스 토큰과는 별개입니다. 보호 API에는 `Authorization: Bearer <accessToken>`을 보내세요.
만료·누락·잘못된 토큰은 401을 반환하므로 다시 로그인합니다. 기존 임의 UUID 데이터는 자동 이관되지 않습니다.

### Postman 테스트

1. 서버와 MySQL을 실행합니다.
2. 브라우저에서 `https://kauth.kakao.com/oauth/authorize?client_id=본인_REST_API_키&redirect_uri=등록한_URI&response_type=code`로 카카오 인가를 시작합니다. 프런트엔드는 state를 생성하고 콜백에서 검증해야 합니다.
3. 콜백 URL의 `code`를 복사해 Postman의 `POST http://localhost:8080/api/v1/auth/kakao` JSON Body에 넣습니다.
4. 성공 응답의 `accessToken`을 Postman Authorization → Bearer Token에 넣습니다.
5. `GET http://localhost:8080/api/v1/auth/me`를 호출해 같은 사용자 정보를 확인합니다.

인가 코드는 일회용입니다. 재시도할 때는 새 코드를 받으세요.
실제 프런트엔드가 아직 없다면 등록한 콜백 페이지가 열리지 않더라도 주소창의 코드를 복사해 수동 테스트할 수 있습니다.

### 자동 테스트

```powershell
.\gradlew.bat test --tests 'com.finsight.auth.*'
```

H2 DB와 모의 카카오 API로 신규 가입, 재로그인, 정책 동의, 만료 토큰, 요청 검증 및 오류 응답을 검사합니다.
실제 카카오 키를 사용하는 종단 테스트는 별도입니다.
[카카오 공식 REST API 문서](https://developers.kakao.com/docs/ko/kakaologin/rest-api)

## 개발 환경

- Java 21
- Spring Boot 4.1.1
- Gradle
- MySQL 8
- IntelliJ IDEA

---

## 1. 저장소 Clone

```bash
git clone https://github.com/HUFS-LikeLion-Team3/BE.git
cd BE
```

IntelliJ에서 `BE` 폴더를 프로젝트로 엽니다.

---

## 2. Java 버전 확인

터미널에서 아래 명령어를 실행합니다.

```bash
java -version
```

Java 21이 설치되어 있어야 합니다.

예시:

```text
java version "21.x.x"
```

Gradle 버전은 아래 명령어로 확인할 수 있습니다.

Windows:

```bash
.\gradlew.bat -v
```

---

## 3. MySQL Database 생성

MySQL Workbench에서 아래 SQL을 실행합니다.

```sql
CREATE DATABASE finsight
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

DB 이름은 `finsight`를 사용합니다.

---

## 4. 환경 변수 설정

DB 계정 정보는 GitHub에 업로드하지 않고 각자 로컬 환경 변수로 설정합니다.

IntelliJ에서 다음 경로로 이동합니다.

```text
실행
→ 실행 구성 편집
→ FinsightApplication
→ 환경 변수
```

`FinsightApplication` 실행 구성이 없는 경우 먼저 아래 파일을 한 번 실행하거나 실행 구성을 생성합니다.

```text
src/main/java/com/finsight/FinsightApplication.java
```

환경 변수는 아래와 같이 설정합니다.

```text
DB_URL=jdbc:mysql://localhost:3306/finsight?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USERNAME=root
DB_PASSWORD=본인 MySQL 비밀번호
```

MySQL 계정명이 `root`가 아닌 경우 `DB_USERNAME`을 본인 계정명으로 변경합니다.

`application.yml`에서는 다음 환경 변수를 사용합니다.

```yaml
spring:
  application:
    name: finsight

  datasource:
    url: ${DB_URL:jdbc:mysql://localhost:3306/finsight?serverTimezone=Asia/Seoul&characterEncoding=UTF-8}
    username: ${DB_USERNAME:root}
    password: ${DB_PASSWORD}

  jpa:
    hibernate:
      ddl-auto: update
    show-sql: true
    open-in-view: false
    properties:
      hibernate:
        format_sql: true

server:
  port: 8080
```

DB 비밀번호, API Key 등 민감한 정보는 코드에 직접 작성하거나 GitHub에 업로드하지 않습니다.

---

## 5. 프로젝트 실행

IntelliJ에서 아래 파일을 실행합니다.

```text
src/main/java/com/finsight/FinsightApplication.java
```

또는 IntelliJ Terminal에서 실행합니다.

```bash
.\gradlew.bat bootRun
```

정상 실행 시 다음과 같은 로그를 확인할 수 있습니다.

```text
Tomcat started on port 8080
Started FinsightApplication
```

기본 서버 주소:

```text
http://localhost:8080
```

---

# Git 작업 방식

## 브랜치 구조

```text
main
  ↑
develop
  ↑
feature/*
```

- `main`: 최종 통합 및 배포용 브랜치
- `develop`: 개발 내용 통합 브랜치
- `feature/*`: 각자 기능 개발 브랜치

기능 개발 중에는 `main`에 직접 작업하거나 직접 push하지 않습니다.

---

## 1. 작업 시작 전 develop 최신화

```bash
git switch develop
git pull origin develop
```

---

## 2. feature 브랜치 생성

반드시 최신 `develop`에서 본인의 작업 브랜치를 생성합니다.

예시:

```bash
git switch -c feature/part1-user-news
```

```bash
git switch -c feature/part2-learning-prediction
```

```bash
git switch -c feature/part3-outcome-feedback
```

기능 단위로 더 세분화해서 만들어도 됩니다.

예시:

```bash
git switch -c feature/outcome-api
```

---

## 3. 작업 내용 확인

```bash
git status
```

`.idea`, `.gradle`, `build` 등 로컬 파일이나 비밀번호가 포함된 파일이 올라가지 않는지 확인합니다.

---

## 4. Commit

```bash
git add .
git status
```

추가된 파일을 확인한 후 commit 합니다.

```bash
git commit -m "feat: 작업 내용"
```

커밋 메시지 예시:

```text
feat: add market outcome API
fix: fix learning session validation
refactor: refactor feedback service
docs: update README
chore: update project configuration
```

---

## 5. Push

최초 push 시:

```bash
git push -u origin feature/브랜치명
```

예시:

```bash
git push -u origin feature/part3-outcome-feedback
```

이후 같은 브랜치에서는:

```bash
git push
```

---

## 6. Pull Request

작업 완료 후 GitHub에서 Pull Request를 생성합니다.

PR 방향:

```text
feature/본인브랜치
        ↓
     develop
```

기능 개발 PR의 Base 브랜치는 반드시 `develop`으로 설정합니다.

```text
base: develop
compare: feature/본인브랜치
```

기능 개발 브랜치를 바로 `main`으로 Pull Request 하지 않습니다.

```text
feature → main ❌
feature → develop ✅
```

---

## 7. main 머지

각 기능 개발 및 통합 테스트가 완료된 후에만 다음과 같이 최종 Pull Request를 생성합니다.

```text
develop
   ↓
 main
```

`main`은 최종 통합 시에만 사용합니다.

---

# 담당 파트

## 개발 파트 1

기능 1 ~ 4

- 사용자
- 온보딩
- 뉴스
- 시장 대상

## 개발 파트 2

기능 5 ~ 7, 10

- 학습 세션
- 예측 대상
- 예측
- 회고

## 개발 파트 3

기능 8 ~ 9

- 시장 결과
- AI 피드백

---

# 주의사항

- Java 21 사용
- MySQL 8 사용
- DB 이름은 `finsight` 사용
- 작업 시작 전 반드시 `develop` 최신화
- 각자 `feature` 브랜치에서 작업
- 기능 PR은 `develop`으로 생성
- `main`에는 직접 기능 작업하지 않음
- DB 비밀번호, API Key 등 민감한 정보는 GitHub에 업로드하지 않음
- `.idea`, `.gradle`, `build`, `.env` 등 로컬 파일은 GitHub에 업로드하지 않음
- `application.yml`에는 실제 비밀번호를 직접 작성하지 않음
- 공통 설정이나 DB 구조 변경 시 팀원과 공유 후 반영
