# FinSight Backend

FinSight 백엔드 프로젝트입니다.

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
