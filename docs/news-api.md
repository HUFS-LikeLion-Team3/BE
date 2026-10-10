# 뉴스 조회 API

## 샘플 데이터

다음 명령으로 서버를 실행하면 설정된 DB에 샘플 데이터가 삽입됩니다.
sample 프로필은 필요할 때만 활성화하며 재실행해도 중복 삽입하지 않습니다.

```powershell
.\gradlew.bat bootRun --args='--spring.profiles.active=sample'
```

| 데이터 | ID |
| --- | --- |
| 공개 Live 미국 뉴스 | 11111111-1111-4111-8111-111111111111 |
| 공개 Live 한국 뉴스 | 22222222-2222-4222-8222-222222222222 |
| 공개 Replay 뉴스 | 33333333-3333-4333-8333-333333333333 |
| 비공개 뉴스 (404 확인) | 44444444-4444-4444-8444-444444444444 |
| 미국 뉴스 출처 문서 | aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa |
| 한국 뉴스 출처 문서 | bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb |

모두 가상 자료이며 제목에 [샘플]을 표시합니다.
기본 목록은 한국 뉴스 → 미국 뉴스 → Replay 뉴스 순서입니다.
관심 시장 us_equity, 관심 주제 rate가 저장된 사용자의 추천은 미국 뉴스 → 한국 뉴스 순서입니다.
로그인 사용자·관심사는 자동 생성하거나 변경하지 않습니다.

모든 API는 Bearer 인증이 필요합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | /api/v1/news | 공개 뉴스 목록 |
| GET | /api/v1/news/{newsId} | 공개 뉴스 상세 |
| GET | /api/v1/news/{newsId}/facts | 주요 사실 및 수치 |
| GET | /api/v1/news/{newsId}/sources | 공개 뉴스 출처 |
| GET | /api/v1/source-documents/{sourceDocumentId} | 저장된 출처 메타데이터 |

목록 기본값: sort=latest, page=0, size=20.
contentType을 생략하면 공개된 Live·Replay 뉴스를 모두 조회합니다.
페이지는 0 이상, 크기는 1~100입니다. 잘못된 조건은 400입니다.
category는 ERD의 varchar이므로 공백이 아닌 255자 이하 문자열입니다.
replayStatus는 ERD의 not_eligible/eligible/featured를 허용합니다.
contentType은 live/replay, sort는 latest/recommended를 허용합니다.

추천 점수는 user_interests의 현재 관심사 일치 개수의 합입니다.
topic의 interest_key는 news.category에, market의 interest_key는
news_target_candidates로 연결된 market_targets.market_category에 대응합니다.
ERD에 관심사 키 사전이 없어 이 대응 규칙을 사용하며, 별도 키 사전 확정 시 조정해야 합니다.
점수 내림차순, 공개 시각 내림차순, ID 오름차순으로 DB에서 정렬 후 페이지를 적용합니다.
관심사가 없으면 최신순이며 일치하지 않은 뉴스도 반환합니다.
관심사 저장/수정 API는 이 조회 API 범위에 포함되지 않습니다.
관심사 입력 경로는 UserInterestRepository를 사용합니다.

뉴스 상세와 출처 조회는 학습 세션을 생성하지 않습니다.
기존 세션 재진입은 GET /api/v1/learning-sessions?newsId={newsId}를 사용합니다.

## 저장 모델

기준: docs/erd.dbml. MySQL은 유지하며 PostgreSQL jsonb는 MySQL JSON으로 대응합니다.
뉴스 조회와 추천은 news, news_facts, source_documents, user_interests,
market_targets, news_target_candidates를 사용합니다.
출처는 source_documents.news_id로 직접 연결합니다.
이전에 임의로 추가한 태그·관심사 분할 테이블과 news_sources는 코드에서 제거했습니다.
기존 DB 데이터 이관은 docs/erd-alignment.md를 따릅니다.

출처 엔티티의 Hibernate Immutable 설정을 유지합니다.
수집/승인 경로에서 공개 시각 및 메타데이터를 채우고,
본문 변경 시 새 문서 ID와 contentHash로 삽입해야 합니다.
이 API는 출처 본문·조각·기존 피드백 참조를 수정하지 않습니다.

## 검증

NewsApiTests는 H2에서 실제 JPA 쿼리와 Bearer 인증을 사용해
추천·관심사 변경·페이지·필터·비공개 자료·출처 응답·세션 미생성을 검증합니다.
