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
기본 목록은 한국 뉴스 → 미국 뉴스 순서입니다.
미국 관심 시장 US, 관심 주제 rates가 저장된 사용자의 추천은 미국 뉴스 → 한국 뉴스 순서입니다.
로그인 사용자·관심사는 자동 생성하거나 변경하지 않습니다.

모든 API는 Bearer 인증이 필요합니다.

| 메서드 | 경로 | 설명 |
| --- | --- | --- |
| GET | /api/v1/news | 공개 뉴스 목록 |
| GET | /api/v1/news/{newsId} | 공개 뉴스 상세 |
| GET | /api/v1/news/{newsId}/sources | 공개 뉴스 출처 |
| GET | /api/v1/source-documents/{sourceDocumentId} | 저장된 출처 메타데이터 |

목록 기본값: contentType=live, sort=latest, page=0, size=20.
페이지는 0 이상, 크기는 1~100입니다. 잘못된 조건은 400입니다.
현재 명세에 명시된 category=rate, replayStatus=not_eligible만 허용합니다.
contentType은 live/replay, sort는 latest/recommended를 허용합니다.
필터 허용 목록 확정 시 NewsService의 검증 목록을 확장해야 합니다.

추천 점수는 현재 저장된 사용자 관심 시장·주제와 일치하는 태그 개수의 합입니다.
점수 내림차순, 공개 시각 내림차순, ID 오름차순으로 DB에서 정렬 후 페이지를 적용합니다.
관심사가 없으면 최신순이며 일치하지 않은 뉴스도 반환합니다.
관심사 저장/수정 API는 이 조회 API 범위에 포함되지 않습니다.
관심사 입력 경로는 UserNewsInterestRepository를 통해 동일한 사용자 ID로 저장해야 합니다.

뉴스 상세와 출처 조회는 학습 세션을 생성하지 않습니다.
기존 세션 재진입은 GET /api/v1/learning-sessions?newsId={newsId}를 사용합니다.

## 저장 모델

새 테이블: news, news_markets, news_topics, news_sources,
user_news_interests, user_interest_markets, user_interest_topics.
source_documents는 기존 피드백 출처 엔티티를 공유하며
source_type, selection_tier, is_primary, retrieved_at 컬럼을 추가합니다.
기존 운영 DB에는 배포 전에 해당 스키마 변경과 기존 출처 메타데이터 보완이 필요합니다.

출처 엔티티의 Hibernate Immutable 설정을 유지합니다.
수집/승인 경로에서 공개 시각 및 메타데이터를 채우고,
본문 변경 시 새 문서 ID와 contentHash로 삽입해야 합니다.
이 API는 출처 본문·조각·기존 피드백 참조를 수정하지 않습니다.

## 검증

NewsApiTests는 H2에서 실제 JPA 쿼리와 Bearer 인증을 사용해
추천·관심사 변경·페이지·필터·비공개 자료·출처 응답·세션 미생성을 검증합니다.
