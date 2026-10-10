# ERD 정합 작업

기준 원문은 erd.dbml입니다. 사용자의 선택에 따라 MySQL을 유지합니다.
이번 수정 범위는 기존 인증·뉴스·사실·출처·추천 저장 모델입니다.
전체 ERD의 미구현 API를 새로 구현한 것은 아닙니다.

| 영역 | 확인/수정 |
| --- | --- |
| users / user_consents | 컬럼, 사용자 유일 키, 동의 유일 키 및 사용자 FK 확인. 기존 사용자 작업 보존 |
| user_interests | id/user_id/interest_type/interest_key/created_at 및 유일 키로 교체 |
| news | published_at/reference_at 필수, created_at/updated_at 추가, ERD 인덱스 추가 |
| source_documents | news_id 직접 FK, 저장 URI/시간 필드/필수 메타데이터/URL·해시 유일 키 추가 |
| news_facts | 출처 문서 FK 및 뉴스·정렬 순서 유일 키 추가 |
| market_targets / news_target_candidates | ERD 컬럼·복합 키·정렬 유일 키 추가, 추천 시장 매칭에 사용 |

관심사 키 사전은 ERD에 없습니다. 현재 topic=뉴스 category,
market=대상 market_category로 대응합니다.
category는 varchar이므로 rate 외 문자열도 허용합니다.
Replay 필터는 not_eligible, eligible, featured 전체를 허용합니다.
contentType 생략 시 P0 기본값 live를 적용하며 Replay는 명시적으로 조회합니다.

## 기존 로컬 DB 이관

현재 DB에 이전 뉴스 모델이 이미 들어 있는 경우 새 서버 실행 전에 이관합니다.
로컬 MySQL 이관 완료 기록은 아래에 있습니다.
SQL은 이전 코드가 만든 테이블들이 있는 DB용이며 빈 DB에서는 실행하지 않습니다.

1. 실행 중인 서버를 중지하고 DB를 백업합니다.
2. migrations/news-erd-mysql-prepare.sql을 MySQL 콘솔에서 실행합니다.
   기존 출처 다중 뉴스 연결, 필수 메타데이터 누락, 중복 유일 키가 있으면 중단합니다.
   source_documents.published_at IS NULL인 행도 DDL·데이터 변경 전에 검사하고 중단합니다.
   누락된 실제 메타데이터는 먼저 보완합니다. 공개 시각·본문 해시를 추정해서 채우지 않습니다.
   생성 시각이 없던 기존 행은 이관 시각으로 기록합니다.
   MySQL DDL은 자동 커밋이므로 실패 시 백업과 실행 로그를 기준으로 확인합니다.
3. sample 프로필로 새 서버를 1회 실행합니다. Hibernate가 새 테이블과 제약을 생성합니다.
4. 서버를 중지하고 migrations/news-erd-mysql-interests.sql과
   migrations/news-erd-mysql-finalize.sql을 순서대로 실행합니다.
   finalize는 source_documents.published_at을 datetime(6) NOT NULL로 확정합니다.
   이전 US/KR/rates 샘플 키를 us_equity/korea_equity/rate로 옮깁니다.
   나머지 키는 원문 그대로 보존하며 키 사전에 맞는지 확인해야 합니다.
5. 이전 뉴스 태그를 새 대상 후보로 일괄 추정하지 않습니다.
   샘플 뉴스는 sample SQL로 대상 후보를 연결합니다.
   실제 뉴스의 후보 대상은 기존 큐레이션 정보로 확정해야 합니다.
6. 서버를 다시 실행하고 뉴스 목록·사실·출처·관심사 추천을 확인합니다.

이전 news_sources/news_markets/news_topics/user_news_interests/
user_interest_markets/user_interest_topics 테이블은 삭제하지 않습니다.
새 코드에서는 참조하지 않으며 이관 검증이 끝난 뒤 별도로 정리할 수 있습니다.

## 남은 ERD 범위

source_document_chunks와 source_document_targets, prediction 및 회고·AI 스냅샷 등은
기존 저장소에 일부 모델이 없거나 관계/제약이 모두 구현되어 있지 않습니다.
이 문서는 전체 시스템이 ERD와 완전히 일치한다고 주장하지 않습니다.
뉴스 API와 무관한 학습·관측·AI 로직을 이번 작업에서 임의 변경하지 않았습니다.

## 검증

H2의 MySQL 모드에서 뉴스·인증·사용자 테스트를 실행했습니다.
출처 직접 연결, 관심사 변경 후 추천, 페이지 정렬, 사실 응답 및 샘플 재입력을 확인합니다.
로컬 MySQL에서도 이관 SQL과 제약 생성을 확인했습니다.

## 로컬 이관 기록 (2026-10-10)

IntelliJ에 저장된 DB 접속 설정으로 localhost:3306/finsight에 적용했습니다.
백업은 local-db-backups/finsight-before-news-erd-20261010-234016.sql에 있으며 Git에서 제외됩니다.
prepare → sample 서버 실행 → 서버 중지 → interests → finalize 순으로 적용했습니다.
Hibernate update가 기존 컬럼의 null 허용과 기본값을 갱신하지 않아 finalize에서 명시적으로 적용했습니다.
사용자 1명, 동의 2건, 뉴스 4건, 출처 2건을 보존했습니다.
요청한 샘플 금리 변동 폭은 제거되어 사실은 2건입니다.
기존 관심사는 0건이며 이전 테이블은 그대로 남겨 두었습니다.
FK·유일 키·필수 컬럼 및 enum 값 CHECK가 실제 MySQL에 생성됐음을 확인했습니다.
서버 재시작 후 실제 HTTP 요청으로 목록·추천·상세·사실·출처·출처 문서의 200 응답,
비공개 뉴스·없는 문서의 404, 미인증 요청의 401을 확인했습니다.
한국 샘플 뉴스의 사실은 기준금리 한 건, 출처는 한 건입니다.
카카오의 실제 인가 코드 교환은 이번 검증에서 실행하지 않았습니다.
