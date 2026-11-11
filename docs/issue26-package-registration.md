# #26 승인 패키지 등록 (수동 실행 도구, schemaVersion 1)

범위: 기존 `source_documents`, `source_document_chunks`, `source_document_targets`에 AI 발행 패키지를 임포트. 별도의 BE CRUD/API/스케줄러 없음. `GET /internal/v1/package`, 실제 DB 인벤토리 확인 이후에만 `POST /internal/v1/package/registration` 호출.

## 보존·매핑 규칙

- 패키지 `packageId`는 `packageId` 제외 객체의 Python canonical JSON SHA-256로 검증하고 원본 값을 보존.
- `document.id`/`stored_chunks[].id`: 동일 UUID를 BE PK로 그대로 사용. 해시와 메타데이터가 다른 기존 행은 `UPDATE`하지 않고 거부. 다른 ID의 `(url, content_hash)` 충돌도 거부.
- 조각 본문의 **공백·개행을 포함한 원문**을 저장. `stored_chunks` 배열 순서를 `chunk_order = 0,1,...`에 대응. 각 `text`의 UTF-8 SHA-256 계산과 결합한 문서의 UTF-8 SHA-256 검증.
- `published_at`: 원래 ISO 오프셋을 가리키는 **동일 시점**을 UTC `DATETIME(6)`에 기록. JDBC 세션 UTC. 확인 시 다시 UTC Instant와 비교.
- `retrieved_at`, `created_at`, `updated_at`: **BE에 등록한 시각(UTC)** 기록. 패키지 `available_at`은 AI 자료 가용 시각이지 실제 수집 시각이라고 단정할 수 없으므로 `retrieved_at`으로 사용하지 않음. 기존 같은 ID 자료 재사용 시 원래 시각을 수정하지 않음.
- `selection_tier`의 패키지 숫자는 `0→primary_official`, `1→original_article`, `2→trusted_research`로 매핑한다(#36). `sourceType`과의 조합을 검증하며, 현재 등록 도구의 등급 2 허용 조합은 `research_report`다. 이 조합 제한이 실제 v2.1 원본 6문서의 메타데이터와 일치하는지는 아직 미검증이므로 원본 패키지 확인 후 확정한다. 범위 밖 등급을 자동 허용하지 않는다. `is_primary=false`, `news_id=NULL`로 새 자료를 삽입하고 기존 뉴스/대표 출처 여부는 변경하지 않음. `linked_news_ids`가 비어 있지 않으면 단일 `news_id`와 손실 없이 매핑할 계약이 없어 거부.
- 패키지에 `document_version` 필드가 없고 현재 BE 테이블에도 동일 컬럼이 없음. **버전 문자열을 지어내지 않는다.** 문서 UUID와 본문 해시를 불변 버전 식별자로 사용하며 이후 별도 버전 필드 요구 시 계약·스키마 합의가 필요.
- 등록 패키지의 `market_target_ids`는 **실제 BE PK와 활성 상태**를 확인해야 함. 누락되거나 다르면 등록 거부; 자료 도구가 시장을 만들거나 후보 ID를 마음대로 바꾸지 않음. 운영자가 별도 검증한 실제 코드·ID 매핑을 확인 후에만 `register` 실행.
- 기존 `source_documents`는 뉴스 출처 API에서도 사용하므로 API 응답/기존 뉴스 데이터를 변경하지 않음. 현재 출처 테이블에 별도 원문 내용 컬럼을 새로 만들지 않고 조각 본문을 순서대로 결합하여 검증.
- MySQL에 대한 모든 변경을 한 트랜잭션에서 실행. 변경 직후 **DB에서 다시 읽은** 조각 본문과 링크를 대조한 다음 커밋. 커밋 이후 새로 조회한 인벤토리로만 AI 확인. 확인 HTTP 실패 시 DB는 변경 상태로 남으며 같은 패키지 재실행 시 데이터가 동일하면 재사용하고 확인을 다시 보냄.

## 사용 방법

`check`는 로컬 JSON만 검사하며 DB/AI에는 연결하지 않음. 반드시 먼저 실행:

```bash
./gradlew issue26Package -Pissue26Mode=check -Pissue26File="$HOME/Downloads/demo-package.json"
./gradlew issue26Package -Pissue26Mode=check -Pissue26File="$HOME/Downloads/approved-package.seed-candidate.json"
```

`preflight`는 실제 DB에서 시장 UUID/기존 문서·조각·연결만 조회하고 데이터를 추가하지 않음:

```bash
export DB_URL='jdbc:mysql://localhost:3307/finsight?serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
export DB_USERNAME=root
read -s DB_PASSWORD; echo; export DB_PASSWORD
./gradlew issue26Package -Pissue26Mode=preflight -Pissue26File="$HOME/Downloads/demo-package.json"
```

#36 별도 복제 스키마에서 preflight만 실행할 경우에는 명시적으로 대상 스키마를 지정한다.
기본값은 여전히 `finsight`이고, 이 옵션은 `finsight_issue36` 외 임의 DB 이름을 허용하지 않는다.
**실제 AI 자료 등록 전에 원본 패키지와 시장 UUID를 확인해야 한다.**

```bash
export ISSUE26_EXPECTED_SCHEMA=finsight_issue36
export DB_URL='jdbc:mysql://127.0.0.1:3306/finsight_issue36?serverTimezone=Asia/Seoul&characterEncoding=UTF-8'
# 사전에 DB_USERNAME, DB_PASSWORD를 로컬 환경 변수로 설정한다.
./gradlew issue26Package -Pissue26Mode=preflight -Pissue26File="$HOME/Downloads/demo-package.json"
# 기존 market UUID가 일치하지 않으면 거절되는 것이 정상. 임의 행/UUID를 생성하지 말 것.
unset ISSUE26_EXPECTED_SCHEMA
```

현재 로컬 market_targets에는 `SAMPLE_KR`·`SAMPLE_US`만 있어 위 preflight가 **시장 UUID 누락으로 실패하는 것이 정상**. 실제 시장 기준정보 담당자와 매핑을 먼저 확정해야 하며, 등록 도구가 샘플 시장과 임의로 연결하지 않는다.

`ISSUE26_VERIFIED_MARKETS`에는 패키지의 모든 시장 UUID에 대해 **실제 BE DB의 code=UUID**를 쉼표로 나열해야 함. 위 `ACTUAL_CODE`와 UUID는 명령 형태 설명용이며 실제 사용할 값이 아님. 일치하지 않으면 등록을 중단함.

실제 AI 서버가 실행 중이고 `GET /internal/v1/package`가 합의된 **불변 승인 패키지+inventory**를 발행하며, 실제 시장 UUID/코드가 확정된 후에만 아래 명시적 등록 명령을 실행한다. 개발 중이라면 합성 demo 패키지가 서버에 발행된 상황에서만 시도한다:

```bash
export AI_RAG_BASE_URL='http://127.0.0.1:8090'
read -s AI_RAG_BEARER_TOKEN; echo; export AI_RAG_BEARER_TOKEN
# 예: CODE=UUID 쌍은 팀에서 실제 DB 코드·ID를 검증한 후 명시적으로 입력 (예시값 복사 금지)
export ISSUE26_VERIFIED_MARKETS='ACTUAL_CODE=00000000-0000-4000-8000-000000000000'
./gradlew issue26Package -Pissue26Mode=register
```

이 작업은 AI 서버에 자료 등록 확인을 보내지만 사용자 공개 피드백 ready까지 바꾸지는 않음. 데이터 수정이나 의미 검토·승인은 AI 담당. BE는 저장 무결성과 확인 영수증 담당.

## 단계별 검증 및 위험

1. 로컬 `check`: 1문서·1조각 demo와 13문서·15조각 후보를 모두 해시 검증. **후보 파일은 실제 승인/등록 완료 증빙이 아님.**
2. `preflight`: 다른 UUID의 동일 URL+해시, 기존 동일 ID의 메타데이터/본문 변경, 조각 UUID·순서/시장 링크 충돌은 거부. 데이터베이스에 없다면 시장 대상부터 별도 준비.
3. `register`: AI 패키지/inventory 일치→BE 트랜잭션에 삽입→실제 DB 원문/링크/해시 대조→commit→DB 재조회→`registered:true`+inventory+receipt 발송.
4. 재실행: 동일 자료면 INSERT/UPDATE 없이 재확인하고 등록 확인. 이미 인용된 자료는 삭제하거나 덮어쓰지 않음.
5. 운영/팀 DB에서는 별도 백업, 스키마 검토 및 시장 매핑 검증 후에만 실행. BE 기동의 `ddl-auto`는 `none`을 권장.

**미구현/연계**: 승인 결과 파싱→원자 저장→blocked/stored delivery 및 재기동 복구는 #26 다른 단계/#27과 통합해야 함. 이 수동 도구 자체가 #26 전체 완료를 의미하지 않음.
