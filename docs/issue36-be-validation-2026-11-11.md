# #36 BE 후속 작업 검증 기록 — 2026-11-11

> 범위: #36 1~3번은 앞선 PR에서 `develop`에 머지된 구현을 재사용했다. 이 기록은 그 이후 BE 로컬 코드·모의 회귀 및 **격리된 개발용 MySQL 스키마**에서 검증한 항목을 정리한다. 원본 v2.1 패키지의 실제 등록과 최신 AI–BE–FE E2E 완료 기록은 아니다.

## 변경 사항

- `ApprovedPackageRegistrationCli`: 숫자 출처 등급 `2`를 BE `trusted_research`로 매핑. 현재 추가한 `tier=2` + `sourceType=research_report` 조합 제한은 **원본 패키지·AI 담당자의 최종 계약 확인 필요**.
- 테스트 추가: 원본 버전(v1/v2.1) 및 v3/v3.1/v3.2의 항목 규칙·승인 파서, `ragSnapshotHash` 관련 기존 회귀, FINAL의 INITIAL 설정·패키지 유지, 관측 결과와 중간 메모 6개 조합, blocked 복구, 재전송·재기동 **모의** 검사.
- `src/test/resources/issue36/`: 해시가 일치하는 **합성 JSON 테스트 fixture** 5개. 운영 또는 원본 v2.1 승인 패키지가 아님.

## 로컬 검증 결과 (작업자 보고)

| 검증 | 결과 | 범위·한계 |
| --- | --- | --- |
| `ApprovedPackageRegistrationCliTest`, 합성 등급 2 `check` | 성공 | 원본 v2.1 6문서 등록 미검증 |
| `ApprovedRagResultParserTest` | 성공 | 실제 v3 서버 결과가 아닌 합성 결과 기반 |
| `FeedbackFrozenSubmissionEventIntegrationTest` 및 전체 `./gradlew clean test` | 성공 | DB 재기동을 수행한 E2E가 아닌 회귀 |
| `AiRagDeliveryCoordinatorTest`, `AiRagPollingSchedulerTest` | 성공 | Mock 기반 응답 유실·재기동 모의 |
| `AiRagFinalInputBuilderTest` | 성공 | ready / mixed / unavailable × 메모 유무 6조합, 누락 스냅샷 |
| 전체 `./gradlew clean test` (6단계 보완 후) | 성공 | 개별 테스트 건수는 별도 Gradle XML 확인 필요 |

## MySQL 검증 (격리 스키마)

- 원본 `finsight`의 로컬 백업을 생성하고 `finsight_issue36`로 복제함. 각 스키마 25개 테이블, AI/RAG 주요 테이블 6개 존재 확인.
- **변경 대상은 `finsight_issue36`만**: `ai_rag_jobs.request_json` LONGTEXT, 승인 원응답·해시·시각 컬럼 3개, dispatch lease 컬럼 2개, 신규 피드백 항목 ENUM 6개, 동일 청크 복수 인용용 인덱스 변경을 점검함.
- 별도 스키마를 대상으로 3개 opt-in MySQL 테스트를 로컬에서 실행하여 성공 보고: 긴 입력 JSON 보존, 승인 결과 원문/해시, dispatch lease. 테스트를 위해 잠시 수정한 하드코딩 DB 경로는 **역패치하여 PR 변경 목록에서 제외**함.
- 검증 후 `ai_rag_jobs=0`, `ai_feedbacks=2`. 원본과 복제본 `ai_feedbacks`는 2건/2건이며 양쪽에만 있는 ID 0건/0건 확인. 이는 **전체 DB 행·내용 동일성 검증을 뜻하지 않음**.
- 사용한 별도 테스트 스키마 마이그레이션은 **로컬에서 검증한 환경 상태**이며 팀/운영 DB에 적용한 것이 아님. `ddl-auto` 자동 변경을 전제로 하지 않는다.

## 아직 완료되지 않은 #36 기준

- 실제 v2.1 **원본 6문서**(등급 2 포함)와 BE `sourceType` 조합 확정, 원본 보존·DB 재조회·AI registration 영수증 검증.
- 실제 실행할 AI v3/v3.1/v3.2 코드·프롬프트·패키지 버전 확정. AI 담당자에 따르면 v3 계열 항목 규칙은 v2.1과 동일하나, 실제 최신 서버 연동은 병합 후 공유 예정.
- 검증 대상 DB의 스키마·시장 코드/UUID·packageId/서버 공유 토큰 합의 후 INITIAL→관리자 승인→저장→FE 표시→FINAL 전체 흐름 확인.
- 승인 전 비노출, NORMAL/일부/전체 unavailable, 회고 메모 유무, 최초 입력 고정, 초기 실패·스냅샷 누락, blocked→자료 보완→동일 원본 stored, 실제 프로세스 재기동·delivery 응답 유실의 **실서버 재현**.
- 실행한 BE/AI/FE Git 커밋과 DB 스키마/패키지 해시, 테스트 결과(성공·실패·skip), 비밀정보 제외 증거 기록. 미실행 항목을 완료로 표시하지 않음.

## PR 및 환경 주의

- 기존 #25·#26·#27의 상태 머신을 새로 구현하지 않았으며, #36 이슈는 전체 연동 완료 전까지 Open 유지.
- 테스트 전용 MySQL 주소 수정은 로컬 확인 후 복원했으므로 PR에는 넣지 않음.
- `.env`, `.env.local`, 토큰, 비밀번호, 인증 데이터, DB 백업 파일은 절대 커밋/첨부하지 않음.
