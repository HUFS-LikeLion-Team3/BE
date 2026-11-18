# P1 Replay 정책 수정 — 2026-11-17

기준: 최신 develop `79f486e`, PR #42(`07fd2a8`)의 Replay 기능 복구 후 정책 보완.

## API / 화면 연동

1. `POST /api/v1/learning-sessions` with `sessionType: "replay"` — 과거 뉴스 재진입 가능.
2. `POST /api/v1/learning-sessions/{learningSessionId}/submit` — 예측 최종 제출. 실제 제출 시각 `submittedAt`, 과거 기준시각 `replayReferenceAt`을 구분해 저장. INITIAL AI의 cutoff는 `replayReferenceAt`.
3. INITIAL AI 피드백 **상세**를 사용자에게 표시하고, 준비 상태(`ready`)를 확인한다. 현재 서버에는 '사용자가 AI 본문을 실제로 읽었는지'를 영속적으로 증명하는 필드는 없다. FE는 피드백 화면에서만 결과 보기 버튼을 노출해야 한다.
4. 사용자가 **결과 보기** 버튼을 누르면 그때만 `POST /api/v1/learning-sessions/{learningSessionId}/outcomes/reveal`을 호출한다. Request body 없음. `200 OK`에 `{ "sessionId": "uuid", "resultsRevealedAt": "datetime" }` 반환. 동일 호출 반복 시 기존 시각 유지.
5. 서버는 Replay + 제출 완료 + **최신 INITIAL 피드백 ready**를 요구한다. 준비 전이나 제출 전은 `409`, Live는 `400`, 타인 세션은 `403`, 없는 세션은 `404`.
6. 공개 전에는 세션 전체/대상별/시계열/수집 이벤트 결과 조회가 모두 `403`. 공개 이후에는 기존 조회 API 사용. `reflection_pending` 상태 전환만으로는 공개되지 않음.

> 중요: 현 정책은 **AI 피드백 ready 상태**를 서버에서 확인하고, **사용자 실제 클릭**으로 공개한다. '피드백 내용을 사용자가 확인했다'는 사실까지 서버에서 별도로 강제하려면 향후 별도 명시적 확인 API와 영속 상태가 필요하며, 현재 ERD에는 없다.

## T0 및 자료

- 대상별 `market_targets.calendar_code`, `timezone`에 따른 거래 세션을 사용해 `referenceAt` **이후** 최초 유효 정규 종가를 T0로 정한다. Live는 실제 `submittedAt` 유지.
- 기준 거래일에 종가 미확보 시 확정된 후속 거래 세션을 탐색한다. 만료 기한 이후에도 Replay **과거 데이터부터 먼저 조회**하고, 확보하지 못했을 때 `unavailable` 처리한다. 관련 T1/T5/T20도 관측 불가 처리하며 임의 수치를 생성하지 않는다.
- INITIAL AI는 과거 기준시각 이후 발행된 뉴스/자료를 제외. 출처가 있는 구조화 사실은 `asOfAt`과 해당 출처의 `publishedAt`/`availableAt`을 함께 확인한다. 출처 미확인 사실은 Replay INITIAL에서 제외.
- FINAL AI는 INITIAL 입력 스냅샷을 유지하고 관측값·회고를 추가하며, 두 frozen final input builder의 기준시각 일치 검사도 Replay에 맞춘다.

## DB 반영

- PR #42에서 도입된 `learning_sessions.replay_reference_at`, `source_documents.available_at` 컬럼이 배포 DB에 없다면 `docs/migrations/20261117_replay_policy_columns.sql` 참고하여 **백업/스키마 확인 후 수동 적용**한다.
- `.env.local` 등의 비밀 파일을 PR에 올리지 않는다.

## 실행 확인

```bash
./gradlew test --tests 'com.finsight.learning.ReplayOutcomeRevealServiceTest' \
  --tests 'com.finsight.learning.LearningSessionServiceTest' \
  --tests 'com.finsight.outcome.service.OutcomeBaselineCoordinatorTest' \
  --tests 'com.finsight.outcome.service.OutcomeServicePendingStateTest'
./gradlew test
```

패치 적용 후 FE/BE 함께 확인할 항목: Replay 제출 전 403, INITIAL 생성 중 공개 409, INITIAL ready 뒤 버튼을 눌렀을 때만 공개, 결과 API 4곳 공개 전 403/후 200, 동일 클릭 중복 시각 불변, 과거 T0 종가/휴장/결측 사례.
