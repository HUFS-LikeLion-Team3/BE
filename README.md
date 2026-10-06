# FinSight Backend

## 개발 환경

- Java 21
- Spring Boot 4.1.1
- Gradle
- MySQL 8
- IntelliJ IDEA

## 프로젝트 실행 방법

### 1. 저장소 Clone

```bash
git clone https://github.com/HUFS-LikeLion-Team3/BE.git
cd BE
```

IntelliJ에서 `BE` 폴더를 프로젝트로 엽니다.

### 2. MySQL Database 생성

MySQL Workbench에서 아래 SQL을 실행합니다.

```sql
CREATE DATABASE finsight
CHARACTER SET utf8mb4
COLLATE utf8mb4_unicode_ci;
```

DB 이름은 `finsight`를 사용합니다.

### 3. 환경 변수 설정

DB 계정 정보는 GitHub에 업로드하지 않고 각자 로컬 환경 변수로 설정합니다.

IntelliJ에서 다음 경로로 이동합니다.

```text
실행 → 실행 구성 편집 → FinsightApplication → 환경 변수
```

아래 환경 변수를 설정합니다.

```text
DB_URL=jdbc:mysql://localhost:3306/finsight?serverTimezone=Asia/Seoul&characterEncoding=UTF-8
DB_USERNAME=root
DB_PASSWORD=본인 MySQL 비밀번호
```

MySQL 계정명이 `root`가 아닌 경우 `DB_USERNAME`을 본인 계정명으로 변경합니다.

`application.yml`에서는 다음과 같이 환경 변수를 사용합니다.

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

비밀번호, API Key 등 민감한 정보는 코드에 직접 작성하거나 GitHub에 업로드하지 않습니다.

### 4. 프로젝트 실행

다음 파일을 실행합니다.

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

## Git 작업 방식

공통 개발 브랜치의 최신 내용을 받은 후 각자 기능 브랜치를 생성합니다.

```bash
git switch develop
git pull origin develop
git switch -c feature/작업명
```

작업 완료 후 본인 feature 브랜치에서 commit 및 push하고 Pull Request를 생성합니다.

## 주의사항

- Java 21 사용
- MySQL 8 사용
- DB 이름은 `finsight` 사용
- DB 비밀번호와 API Key 등 민감한 정보는 GitHub에 업로드하지 않음
- `.idea`, `build`, `.gradle`, `.env` 등 로컬 파일은 Git에 업로드하지 않음
- 공통 설정 변경 시 팀원에게 공유 후 반영
