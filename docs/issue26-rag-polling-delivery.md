# FinSight #26 — 9단계: 영속 polling, 결과 저장 및 delivery 연결

적용 기준: BE `develop 28e9771` + #26 1~7차 패치. 기존 `FinsightApplicationTests` H2/JWT 설정 수정과 파일 충돌 없음.

## 실행 제어

- **기본값 `AI_RAG_POLLING_ENABLED=false`**. patch 적용, `./gradlew test`, DB 조회만으로 자동 polling이 시작되지 않는다.
- 운영/로컬의 안전한 DB 접속 정보, AI 서버 토큰, `AI_RAG_PACKAGE_ID`, `SPRING_JPA_HIBERNATE_DDL_AUTO=none`을 확인한 뒤 별도의 `FinsightApplication-issue26` 구성에서 명시적으로 켠다.
- `AI_RAG_POLLING_INTERVAL_MS=5000`(기본 5초); 검토 대기와 blocked는 30초, 통신 예외 시 5~60초 지수 backoff(프로세스 메모리, 재시작 시 초기화).
- 승인된 원본이 없는 `awaiting_storage`와 불변 ID 불일치 등은 `reconciliation_required`로 남긴다. AI 생성 재호출/`/retry` 금지.
- `@Scheduled`는 활성화된 경우만 생성된다. 모든 작업 후보를 `ai_rag_jobs`와 `ai_feedbacks`의 실제 상태에서 재조회하므로 재기동 후에도 수락된 작업을 재개한다.

## 동작 순서

1. AI의 `GET /internal/v1/jobs/{jobId}` 응답을 **실제 고정 request의 immutable ID/BE 입력 hash와 비교**한다.
2. `ready`: 이미 구현된 `fetchApprovedResult()`가 원본 JSON과 `resultHash`를 별도 커밋으로 저장한다.
3. `ApprovedFeedbackPersistenceService.persistCaptured()`에서 인용된 문서·조각을 재검증하고 feedback/items/references/links/ready를 단일 DB 트랜잭션으로 저장한다.
4. DB 커밋 후 `POST /internal/v1/jobs/{jobId}/delivery`에 `stored`, 원래 resultHash, 영수증을 전달하고 성공한 다음 로컬 작업 상태를 `stored`로 기록한다.
5. 출처 누락/해시 불일치·저장 오류: 트랜잭션 롤백, 원본은 보존. 로컬 상태 `blocked`를 기록하고 **동일 resultHash**로 AI에 `blocked`를 통보한다. DB가 보완되면 기존 원본으로만 저장 재시도한다.
6. DB ready 이후 delivery 요청이 실패했다면 **기존 피드백을 다시 쓰지 않고** stored 통보만 재시도한다. 다른 worker에서 저장 완료된 경우도 동일하다.
7. AI `failed`는 BE failed로 반영하고 자동 retry를 요청하지 않는다. 검토 대기(`awaiting_review`)는 결과 본문을 읽지 않는다.

## 한계 및 후속 통합 검증

- #25가 수락 전 `jobId` 없이 종료된 작업에 대한 **GET beRequestId 조회 기반 복구**는 이 패치의 자동 scheduler에 포함하지 않았다. 이 패치는 `jobId IS NOT NULL`의 영속 작업 복구를 담당한다.
- #27의 관리자 승인 UI/실제 승인 판정, end-to-end INITIAL→FINAL 실제 세션 통합은 별도 검증이 필요하다.
- `providerMode=demo`인 AI는 합성 응답 모드다. 실제 모델 품질 검증이 아니다.
- 별도 DB DDL 없음. 기존 승인 패키지 문서 15·조각 15·연결 11을 변경하는 migration 없음.

## 검증

```sh
./gradlew test --tests 'com.finsight.feedback.service.AiRagDeliveryCoordinatorTest' \
               --tests 'com.finsight.feedback.service.AiRagDeliveryStateTest' \
               --tests 'com.finsight.feedback.client.AiRagJobDeliveryClientTest' \
               --tests 'com.finsight.feedback.service.AiRagPollingSchedulerTest'
./gradlew test
```

**검증 통과 전 자동 polling을 켜지 말 것.**
