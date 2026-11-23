# Issue #26, 단계 8 — 승인된 피드백 원본 → BE 원자 저장

대상: `develop 28e9771` + 기존 1~6차 패치가 적용된 #26 작업 브랜치.

## 변경 범위

- `FeedbackGenerationService.storeApprovedResult(feedbackId)` 신규 진입점: 먼저 AI 원본을 `ai_rag_jobs.approved_result_json`에 **별도 커밋**으로 보관한 뒤, 전용 저장 서비스를 호출한다. 기존 `fetchApprovedResult()` 동작은 유지한다.
- `ApprovedRagResultParser`: 보관 원본만 파싱. 작업 ID·세션·단계·요청·BE 입력 해시·패키지 ID를 고정 입력과 대조한다. 응답 `resultHash`와 output canonical SHA-256을 검증한다. sessionItems/targetFeedbacks/references, 순서·타입·연결을 검증하고 본문/발췌를 가공하지 않는다.
- `ApprovedSourceIntegrityVerifier`: 인용된 문서의 **실제 MySQL 조각 본문을 순서대로 연결하여 문서 content_hash와 대조**한다.
- `ApprovedFeedbackPersistenceService`: feedback 및 작업 행을 잠그고 항목, reference, item-reference, `ready` 전환을 한 트랜잭션에 수행한다. 이미 ready라면 중복 저장하지 않는다. DB의 `sort_order`는 피드백 전체에서 UNIQUE이므로 AI의 scope별 정렬을 유지하여 글로벌 순서를 새로 부여한다.
- 완료된 AI 실행 메타데이터 (`modelVersion`, `promptVersion`, `policyVersion`, `generatedAt`)는 승인 결과에서 취득한다.

## 테스트 (DB 쓰기 및 AI HTTP 호출 없음)

```sh
./gradlew test --tests 'com.finsight.feedback.service.ApprovedRagResultParserTest' \
               --tests 'com.finsight.feedback.service.ApprovedSourceIntegrityVerifierTest' \
               --tests 'com.finsight.feedback.service.ApprovedFeedbackPersistenceServiceTest' \
               --tests 'com.finsight.feedback.service.FeedbackGenerationServiceTest'
```

전체 회귀 테스트:

```sh
./gradlew test
```

기존 DB의 데이터는 이번 패치 적용/테스트만으로 바뀌지 않는다. **새 테이블/DDL 없음.**

## 경계와 남은 작업

- 이 단계는 승인 원본 → DB 원자 저장의 진입점만 마련한다. 아직 스케줄러/영속 polling이 `storeApprovedResult`를 호출하지 않고, `/jobs/{jobId}/delivery`의 `blocked/stored` 알림도 자동화하지 않았다. **로컬 AI에 실제 작업이 생성된 적 없는 상태에서 바로 ready 연동을 주장할 수 없다.**
- 실제 AI `output`의 각 reference는 `id` 또는 `referenceId`, `chunkId` 또는 `sourceDocumentChunkId`, `sourceDocumentId`, `citationExcerpt`, `usageContext`를 제공해야 한다. 두 별칭이 동시에 존재해 다르면 실패한다. 계약과 다른 필드라면 **실제 AI 출력에 맞춰 명시적으로 조정**하고, 발췌를 합성하거나 잘라내지 않는다.
- `resultHash`는 패키지 ID와 달리 **prefix 없는 소문자 SHA-256 hex**라는 v1 HTTP 계약을 따른다. Python `sort_keys=True, ensure_ascii=False, separators=(',',':')`와 호환되는 canonical JSON으로 검증한다. JSON 숫자 형식 등 AI의 canonical 구현에 차이가 있으면 실제 승인 응답 사례를 기준으로 확인해야 한다.
- `providerMode=demo`인 동안에는 실제 유료 모델 품질을 검증한 것이 아니다.
- 등록된 13문서·15조각·11연결과 기존 문서 2건을 직접 삭제/수정하지 말 것.
