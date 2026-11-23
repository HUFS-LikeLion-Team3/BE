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

## AI `dev` HTTP/프로세스 재기동 독립 검증 (2026-11-11)

- AI `dev` `6390b69` 압축본 기준, Python `runtime/tests` **176개 통과**. 이 검사는 BE Gradle 검사와 별개임.
- `scripts/issue36_ai_http_smoke.py`로 **별도 임시 SQLite** 및 AI `demo` HTTP 서버를 띄워 다음을 검증: 인증·등록 전 차단, INITIAL 접수 멱등성/승인 전 409, INITIAL 승인 중 FINAL `awaiting_initial`, FINAL 검토/승인, 동일 청크의 서로 다른 인용 2개, blocked→stored·늦은 blocked 거절, AI **프로세스 종료 후 실제 재실행** 시 작업 ID·RAG 해시·결과 해시·등록 상태 유지.
- 검증에 사용된 등록 영수증은 명시적인 **`LOCAL_TRANSPORT_DOUBLE_NO_BE_DATABASE`**. 따라서 이 결과는 AI 서버의 프로토콜/SQLite 회복력 증거이며 **BE/실제 MySQL 저장 및 FE 연동 증거가 아님**. 유료 모델 호출 0건.
- 실행 예: `python3 scripts/issue36_ai_http_smoke.py --repo "$HOME/Desktop/finsight-ai" --out "$HOME/Desktop/issue36-ai-http-smoke-report.json"` (AI 프로젝트의 Python 3.12 가상환경 및 `requirements-test.txt` 필요; macOS 기본 Python 3.9.6은 실행 불가). 보고서 JSON에는 토큰·암호를 저장하지 않음.

## 복제 DB용 등록 도구 확인

- 기본 등록 도구는 정확히 스키마 `finsight`만 허용하며, `ISSUE26_EXPECTED_SCHEMA=finsight_issue36`을 **명시적으로 설정한 경우에만** 로컬 복제 스키마를 허용하도록 안전 검사 추가. 다른 DB/URL은 거절.
- 이는 복제 DB `preflight`를 가능하게 하는 코드 변경이며, 원본 패키지 `register`나 실제 시장 UUID/AI 등록 영수증 검증이 완료되었다는 의미가 아님.

## 로컬 실행 확인 — AI HTTP, 복제 MySQL preflight, BE 재시작 (2026-11-11, 작업자 보고)

- AI `dev` HTTP 스모크를 macOS **Python 3.12** 전용 가상환경으로 재실행해 `result: PASS` 확인. 검증 5개 영역: 인증·데모 등록 차단, INITIAL 멱등/승인 전 비노출, INITIAL→FINAL 검토 및 인용 식별, blocked→stored/중복·늦은 delivery 거절, **AI 서버 실제 프로세스 재시작 후 SQLite 보존**. 보고서에는 `scope: AI dev HTTP demo only; NO BE/MYSQL/FE and NO paid model` 명시. 사용자 실행 시각(UTC): `2026-11-11T07:33:21.903512+00:00`.
- AI 데모 패키지 ID `sha256:3f8f06b3d8c41b2b0035ecdcdb7c3a2de529431615984ac68d3091351f23226e`는 **문서 1개·청크 1개**인 합성 데모이며, 원본 6문서 패키지가 아님. 요구 시장 UUID `c5d99dc5-0eff-5509-a1a9-1aa4be9750db`는 `finsight_issue36.market_targets`에서 **0행** 확인.
- `issue26Package -Pissue26Mode=preflight`를 격리 DB에 실행하자 JSON 검사 자체는 통과하고, `market_target_id not present in actual BE DB: c5d99dc5-0eff-5509-a1a9-1aa4be9750db`로 중단됨. 이는 **등록 전 시장 UUID 거절 동작 성공**으로 기록하되, Gradle 태스크 상태는 `BUILD FAILED`이며 정상 등록·DB 쓰기·등록 영수증 검증 결과가 아님. 최초 시도는 `DB_PASSWORD` 미설정으로 실패했으며, 환경변수를 설정한 뒤 UUID 거절까지 진행함.
- Spring Boot를 로컬 포트 `18080`으로 기동한 뒤 인증 없는 `/api/v1/feedbacks/00000000-0000-4000-8000-000000000000` 요청이 **HTTP 401** 및 `WWW-Authenticate: Bearer`를 반환함. `finsight_issue36`의 `information_schema.PROCESSLIST`에서 `Query=1`, `Sleep=10` 세션 관찰(개별 연결의 소유 프로세스는 확정하지 않음).
- **BE 프로세스 종료·재실행** 뒤 같은 HTTP 요청이 다시 401 응답. 별도 Workbench 조회에서 재실행 **전후 행 개수** `ai_rag_jobs=0`, `ai_feedbacks=2`, `market_targets=2`로 일치함. 이는 **기본 데이터 수량 보존 및 서버 재기동 가능**의 증거이며, 행 내용·해시 비교나 승인 작업 재전송/복구 E2E 결과는 아님. BE 기동 시 Hikari/JPA 시작 로그는 별도 증거로 저장하지 않았음.
- AI 최신 v3 계열 프로토콜과 실제 BE–AI–MySQL–관리자–FE의 end-to-end 연동, 원본 6문서 정상 등록 및 배송 응답 유실 실서버 재현은 **미검증**으로 유지함.

## 아직 완료되지 않은 #36 기준

- 실제 v2.1 **원본 6문서**(등급 2 포함)와 BE `sourceType` 조합 확정, 원본 보존·DB 재조회·AI registration 영수증 검증.
- 실제 실행할 AI v3/v3.1/v3.2 코드·프롬프트·패키지 버전 확정. AI 담당자에 따르면 v3 계열 항목 규칙은 v2.1과 동일하나, 실제 최신 서버 연동은 병합 후 공유 예정.
- 검증 대상 DB의 스키마·시장 코드/UUID·packageId/서버 공유 토큰 합의 후 INITIAL→관리자 승인→저장→FE 표시→FINAL 전체 흐름 확인.
- 승인 전 비노출, NORMAL/일부/전체 unavailable, 회고 메모 유무, 최초 입력 고정, 초기 실패·스냅샷 누락, blocked→자료 보완→동일 원본 stored, 실제 **BE–AI 연결 상태**에서의 재기동·delivery 응답 유실 재현.
- 실행한 BE/AI/FE Git 커밋과 DB 스키마/패키지 해시, 테스트 결과(성공·실패·skip), 비밀정보 제외 증거 기록. 미실행 항목을 완료로 표시하지 않음.

## PR 및 환경 주의

- 기존 #25·#26·#27의 상태 머신을 새로 구현하지 않았으며, #36 이슈는 전체 연동 완료 전까지 Open 유지.
- 테스트 전용 MySQL 주소 수정은 로컬 확인 후 복원했으므로 PR에는 넣지 않음.
- `.env`, `.env.local`, 토큰, 비밀번호, 인증 데이터, DB 백업 파일은 절대 커밋/첨부하지 않음.
