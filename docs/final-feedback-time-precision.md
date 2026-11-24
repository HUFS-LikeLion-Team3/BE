# FINAL 입력 준비의 시각 정밀도와 실패 표시

## 문제와 변경

INITIAL 입력 JSON은 제출/Replay 기준 시각을 나노초까지 보존하지만 MySQL DATETIME(6)은 마이크로초로 반올림한다. 예: `.589211844` → `.589212`. 두 Instant를 완전 일치로 비교하면 정상 제출도 `initial_submission_changed`로 거절되어 FINAL을 접수하지 못한다.

`SubmissionTimePrecision`은 두 시각을 MySQL 기본 반올림 정밀도로 비교한다. 원본 JSON, 해시, 검색 기준 시각은 바꾸지 않는다. 실제 다른 마이크로초 또는 다른 제출 시각은 거절한다. Live 제출 시각과 Replay의 `outcomeReferenceAt` 선택 규칙은 유지한다.

입력 준비 실패로 requestJson/jobId가 없는 작업만 `reconciliation_required`로 보관하고 공개 feedback은 `failed` 및 기존 failureCode를 반환한다. 정상 진행·승인 결과는 덮어쓰지 않는다. API 필드와 스키마는 변경하지 않는다. FE는 기존 failureCode로 관리자 확인 안내를 표시할 수 있다.

전제: DATETIME(6), 기본 반올림 모드. `TIME_TRUNCATE_FRACTIONAL` 모드를 켜는 경우 이 비교 규칙도 함께 재검토해야 한다. [MySQL 공식 문서](https://dev.mysql.com/doc/refman/8.4/en/fractional-seconds.html).

## 검증

- 전체 `./gradlew test`: 466개, 실패/오류 0, 건너뜀 20. 외부 연동용 건너뛴 테스트를 통과로 간주하지 않는다.
- 실제 발생값, 500ns 반올림 경계, 다음 날로 넘어가는 경계, offset 차이, null·변경된 제출 시각, Live/Replay 입력 보존을 검증한다.
- H2 트랜잭션 테스트로 회고 이벤트 후 입력 준비 실패가 별도의 실패 상태·코드를 남기고 AI 요청을 만들지 않음을 확인한다.
- 운영 MySQL은 읽기 전용으로 DATETIME_PRECISION=6, truncation mode 비활성, CAST 반올림 결과를 확인했다. 운영 데이터 변경 및 실제 모델 호출은 하지 않았다.

## 기존 중단 작업의 복구

이 변경은 기존 `generating`/`reconciliation_required` 행을 자동 변경하지 않는다. 입력 스냅샷이 없는 FINAL을 현재 DB로 임의 재작성하거나 스케줄러로 자동 재접수하면 제출 당시 입력 보존 원칙을 어긴다.

1. 배포 전 해당 feedback/job/INITIAL 스냅샷/원본 회고 제출·관측 기록을 비공개 백업한다. 현재 상태와 준비 오류 코드, AI jobId가 없음을 확인한다.
2. 제출 당시 FINAL에 포함될 회고·관측 값과 cutoff를 재현할 수 있는지 검토한다. 현재 조회 값이 같다고 가정하지 않는다. 원본을 복원할 수 없으면 복구 불가로 유지하고 오류를 안내한다.
3. 재현 가능한 경우에만 별도 승인된 단일 세션 정합성 복구 절차로 원본 INITIAL을 재사용한 FINAL 스냅샷을 준비한다. 기존 row를 무조건 pending으로 바꾸지 않는다.
4. 중복 요청/모델 호출이 없도록 동일 식별자와 해시를 확인한 뒤 접수한다. 생성 이후 관리자 검토·승인, BE 저장, FE 노출을 차례로 확인한다.

이번 변경에는 운영 배포, 기존 작업 재구성, 상태 변경 SQL, 내용 승인, 유료 호출을 포함하지 않는다.
