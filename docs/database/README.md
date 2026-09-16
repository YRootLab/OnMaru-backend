# OnMaru DBML ERD

이 디렉터리는 OnMaru 백엔드 설계의 DBML 기반 ERD를 관리한다. 현재 저장소에는 Spring Boot 애플리케이션 scaffold와 D01 Flyway baseline migration이 있다. DBML은 업무 테이블의 논리 설계 source이고, Flyway SQL은 실행 DB를 변경하는 기준이다.

## 현재 기준

OnMaru ERD의 source of truth는 DBML이고, GitHub에서 사람이 볼 시각 자료는 Azimutt에서 export한 PNG다.

| 목적 | 기준 |
|---|---|
| 설계 원본 | `docs/database/schema.dbml`, `docs/database/modules/*.dbml` |
| 무료 웹 시각화 | Azimutt |
| Azimutt import 파일 | `docs/database/azimutt/onmaru-schema.azimutt-strict.sql` |
| GitHub 공유 이미지 | `docs/database/azimutt/exports/*.png` |
| 운영 migration | `apps/spring-api/src/main/resources/db/migration/baseline`의 Flyway SQL |
| migration registry | `db/migration/registry/migrations.json` |

실행 DDL과 경쟁 상태의 구현 기준은 [migration and concurrency proof plan](migration-and-concurrency.md)에 둔다. D01 baseline은 schema namespace, version registry, role grant를 만들고, 후속 업무 테이블 migration은 registry의 예약 규칙을 따라 새 Flyway version으로 추가한다.

ChartDB, drawDB, ERDCloud, dbdiagram.io, D2, Graphviz 산출물은 기본 경로에서 제외했다. Azimutt가 무료로 잘 동작하고, 모듈별 view를 PNG로 export해서 GitHub에 올리는 흐름이 현재 프로젝트에 가장 단순하다.

## 파일 구조

```text
docs/database/
├── README.md
├── schema.md
├── overview.dbml
├── schema.dbml
├── azimutt/
│   ├── README.md
│   ├── onmaru-schema.azimutt-strict.sql
│   ├── onmaru-schema.azimutt.sql
│   ├── onmaru-schema.postgres.sql
│   └── exports/
│       ├── README.md
│       └── *.png
└── modules/
    ├── identity.dbml
    ├── catalog.dbml
    ├── audio.dbml
    ├── insights.dbml
    ├── discovery.dbml
    ├── journey.dbml
    ├── community.dbml
    ├── operations.dbml
    └── ai.dbml
```

실행 migration registry는 repository root의 `db/migration/registry/migrations.json`에서 관리한다. Spring Boot는 `spring.flyway.locations=classpath:db/migration/baseline`으로 baseline migration을 읽는다.

## 빠른 실행

DBML을 수정한 뒤 Azimutt import 파일을 다시 만들려면 아래 명령을 실행한다.

```bash
node scripts/azimutt-export.mjs
```

Azimutt에는 아래 파일을 import한다.

```text
docs/database/azimutt/onmaru-schema.azimutt-strict.sql
```

이 파일은 Azimutt parser가 경고를 내기 쉬운 PostgreSQL 방언을 낮춘 시각화 전용 SQL이다. 운영 migration 기준으로 사용하지 않는다.

DBML 문법과 import 해석이 깨지지 않았는지 확인하려면 PostgreSQL 변환 검증을 실행한다.

```bash
npx -y -p @dbml/cli dbml2sql docs/database/schema.dbml --postgres
```

`overview.dbml`은 Level 1 System Overview다. 핵심 anchor table과 cross-module FK만 보여준다.

`schema.dbml`은 Level 2 Domain Detail entrypoint다. DBML module system의 `use * from './modules/name'` 구문으로 모듈 파일을 import한다. Azimutt import 산출물은 이 entrypoint의 import를 펼쳐서 생성한다.

## Azimutt에서 보는 방법

1. <https://azimutt.app/>에 접속한다.
2. 새 project를 만든다.
3. SQL import 또는 PostgreSQL SQL import를 선택한다.
4. `docs/database/azimutt/onmaru-schema.azimutt-strict.sql`을 업로드하거나 내용을 붙여넣는다.
5. 모든 테이블을 한 번에 정리하지 말고, `identity_`, `catalog_`, `discovery_`, `journey_`, `community_`, `operations_` 같은 prefix로 focus view를 만든다.
6. 배치가 끝난 view를 PNG로 export해서 `docs/database/azimutt/exports/`에 저장한다.
7. PR 설명, 설계 리뷰 문서, GitHub markdown에는 Azimutt에서 export한 PNG를 기본 이미지로 첨부한다.

권장 PNG 파일명은 다음과 같다.

| File | 용도 |
|---|---|
| `onmaru-erd-system-overview.png` | 전체 bounded context와 cross-module FK 공유 |
| `onmaru-erd-identity-auth.png` | 회원, OAuth provider, guest/session 구조 공유 |
| `onmaru-erd-catalog-kto.png` | 한국관광공사 국문 수집, 장소, 지역, revision 구조 공유 |
| `onmaru-erd-discovery-journey.png` | 자연어 탐색, 저장 여정, 월간 타임라인 흐름 공유 |
| `onmaru-erd-map-community.png` | 지도 후기, 좋아요, 장소/지역 연결 공유 |
| `onmaru-erd-operations-sync.png` | 새벽 3시 수집, checkpoint, lease, quarantine 복구 흐름 공유 |
| `onmaru-erd-ai-rag.png` | optional RAG corpus/chunk/embedding 경계 공유 |

자세한 view 추천은 [Azimutt README](azimutt/README.md)에 둔다.

## 현재 구조 분석 결과

| 항목 | 확인 결과 |
|---|---|
| 실제 DB schema / migration / DDL | D01 baseline migration만 존재한다. 업무 table DDL은 후속 D02-D09 migration 대상이다. |
| JPA Entity / Repository | 없음. Java/Kotlin source, Gradle, Maven manifest가 없다. |
| FK 및 constraint | 실행 DB 기준 FK 없음. 최신 planning 문서의 FK/unique/check 제안을 DBML로 옮겼다. |
| Spring package/module 구조 | 구현 전이다. 설계상 core는 identity, catalog, discovery, journey, community이며 audio/insights/ai는 후속 모듈이다. |
| 기존 schema 문서 | `schema.md`는 보존하되 전체 modern schema의 source는 `schema.dbml`이다. |
| 도메인 간 참조 | `schema.dbml` 하단의 cross-module Ref가 현재 결합 지점이다. |

## 테이블 목록

| Module | Tables |
|---|---|
| Identity | `identity_members`, `identity_external_accounts`, `identity_sessions`, `identity_guests`, `identity_oauth_states`, `identity_exploration_grants` |
| Catalog | `catalog_regions`, `catalog_region_source_codes`, `catalog_region_boundaries`, `catalog_dataset_revisions`, `catalog_active_datasets`, `catalog_place_identity`, `catalog_place_sources`, `catalog_place_versions`, `catalog_kto_korean_content_versions`, `catalog_kto_korean_intro_versions`, `catalog_kto_korean_info_versions`, `catalog_place_image_versions`, `catalog_hanok_detail_versions` |
| Audio | `audio_odii_spots`, `audio_odii_stories`, `audio_spot_versions`, `audio_story_versions`, `audio_subtitle_lines`, `audio_place_odii_links` |
| Insights | `insights_visitor_observations`, `insights_tourism_targets`, `insights_target_place_links`, `insights_concentration_observations` |
| Discovery | `discovery_explorations`, `discovery_runs`, `discovery_run_commands`, `discovery_proposals`, `discovery_turns` |
| Journey | `journey_saved_journeys`, `journey_saved_resources` |
| Community | `community_visit_reviews`, `community_review_likes` |
| Operations | `operations_idempotency`, `operations_admission`, `operations_admission_audit`, `operations_sync_schedules`, `operations_sync_runs`, `operations_sync_checkpoints`, `operations_sync_leases`, `operations_sync_watermarks`, `operations_sync_quarantine` |
| AI | `ai_documents`, `ai_chunks`, `ai_embeddings` |

## 모듈 분류 이유

Identity는 내부 회원 UUID, OAuth 외부 계정, guest/session/state/grant를 소유한다. Kakao/Google/Naver subject는 이 모듈 밖의 PK가 아니다.

Catalog는 한국관광공사 국문 관광정보, 지역, 장소 canonical identity, source mapping, dataset revision, 이미지와 한옥 상세를 소유한다. 국문 TourAPI의 `contentid`, `contenttypeid`, `areacode`, `sigungucode`, `cat1~3`, `detailIntro2`, `detailInfor2`, `detailImage2`, `areaBasedSyncList2.showflag`는 `catalog_kto_korean_*_versions`에 명시했다.

Audio는 Odii spot/story 안정 ID, versioned story/script/audio URL, subtitle line, 장소-Odii 검수 연결을 소유한다. 장소와 연결은 cross-module이지만 오디 원본의 정답은 Audio가 가진다.

Insights는 지역 방문자수와 관광지 집중률 같은 통계성 관측값을 소유한다. 이 값은 실시간 사용자 수가 아니라 공공데이터 기반 추정치다.

Discovery는 자연어 탐색 workspace, durable run, command receipt, proposal, turn을 소유한다. 장소·지역은 JSON ref와 FK로 참조하지만 catalog를 수정하지 않는다.

Journey는 저장 여정 snapshot, 장소/Odii 담아두기, 내 월간 타임라인의 source row를 소유한다. `journey_saved_resources.resource_id`는 PLACE/Odii polymorphic pointer라서 DBML에서 조건부 FK로 표현하지 않고 application eligibility port로 검증한다.

Community는 짧은 방문 후기와 좋아요를 소유한다. 기존 Warmth mood/score 모델과 분리한다.

Operations는 idempotency, admission counter/audit, 03:00 KST sync schedule/run/checkpoint/lease/watermark/quarantine을 소유한다.

AI는 optional RAG 승인 후 사용하는 corpus/chunk/embedding schema다. MVP에서는 FastAPI에 DB credential을 주지 않으므로 실제 migration 대상이 아니다.

## Intra-module relation

| Module | 내부 관계 |
|---|---|
| Identity | external account/session/grant는 member를 참조한다. oauth state는 guest를 참조한다. |
| Catalog | region은 self hierarchy를 가진다. place source/version/image/hanok/KTO Korean source version은 place identity, source ref, dataset revision을 참조한다. |
| Audio | story는 spot을 참조한다. spot/story/subtitle version은 stable Odii identity를 참조한다. |
| Insights | target place link와 concentration observation은 tourism target을 참조한다. |
| Discovery | run/proposal/turn은 exploration을 참조하고 proposal은 run에 1:1로 묶인다. run command receipt는 run을 참조하고 command idempotency replay와 payload mismatch를 기록한다. |
| Journey | 내부 FK는 의도적으로 적다. saved resource polymorphic target은 application 검증 대상이다. |
| Community | review like는 visit review를 참조한다. |
| Operations | checkpoint와 quarantine은 sync run을 참조한다. |
| AI | chunk는 document를, embedding은 chunk를 참조한다. |

## Cross-module relation

| From | To | 이유 / 결합 의미 |
|---|---|---|
| `identity_oauth_states.exploration_id` | `discovery_explorations.id` | 로그인 후 익명 탐색 소유권 이전/저장 intent 보존 |
| `identity_exploration_grants.exploration_id` | `discovery_explorations.id` | guest exploration을 회원이 짧게 저장할 수 있는 grant |
| `discovery_explorations.owner_member_id` | `identity_members.id` | 회원 소유 탐색 |
| `discovery_explorations.owner_guest_id` | `identity_guests.id` | 비회원 임시 탐색 |
| `discovery_explorations.region_id` | `catalog_regions.id` | 탐색의 지역 조건 |
| `journey_saved_journeys.member_id` | `identity_members.id` | 회원 개인 저장 |
| `journey_saved_journeys.source_exploration_id` | `discovery_explorations.id` | 저장 여정의 원본 탐색 |
| `community_visit_reviews.member_id` | `identity_members.id` | 작성자 소유권 |
| `community_visit_reviews.place_id` | `catalog_place_identity.id` | 공개 장소 대상 후기 |
| `community_review_likes.member_id` | `identity_members.id` | 회원별 좋아요 유일성 |
| `audio_place_odii_links.place_id` | `catalog_place_identity.id` | 검수된 장소-Odii 연결 |
| `insights_visitor_observations.revision_id` | `catalog_dataset_revisions.id` | 통계 관측값의 수집 revision 추적 |
| `insights_visitor_observations.region_id` | `catalog_regions.id` | 지역 단위 방문자 관측 |
| `insights_tourism_targets.region_id` | `catalog_regions.id` | 관광지 집중률 대상의 행정구역 |
| `insights_target_place_links.place_id` | `catalog_place_identity.id` | 외부 관광지 target과 canonical place 연결 |
| `operations_sync_runs.revision_id` | `catalog_dataset_revisions.id` | sync run과 staging/published revision 연결 |
| `operations_sync_watermarks.revision_id` | `catalog_dataset_revisions.id` | LKG revision pointer 추적 |

## 결합도와 주의점

`discovery_explorations`가 Identity와 Catalog를 직접 FK로 참조한다. 이는 사용자 소유권과 지역 조건을 안정적으로 확인하기 위한 의도된 결합이다. 애플리케이션 레이어에서는 Discovery가 Identity/Catalog 구현체를 직접 호출하지 않고 inbound use case와 outbound eligibility/read port로 접근해야 한다.

`journey_saved_resources.resource_id`는 polymorphic pointer다. PLACE와 ODII_STORY를 한 테이블에 담아 월간 타임라인을 단순화하지만 DB FK만으로는 대상 존재성을 강제할 수 없다. 구현 시 Journey application service가 CatalogPlaceReadPort와 AudioStoryReadPort를 통해 저장 가능 여부를 검증해야 한다.

Community는 `catalog_place_identity`를 FK로 참조한다. 지도 후기의 대상 장소는 Catalog가 소유하고, Community는 후기와 좋아요만 소유한다. 장소명, 주소, 좌표는 조회 DTO에서 Catalog projection과 join하거나 read model로 제공한다.

Operations는 여러 도메인의 sync를 orchestration하지만 도메인 데이터를 직접 소유하지 않는다. 실제 구현에서는 Operations가 Catalog table을 직접 수정하는 gateway가 아니라 sync job port를 통해 revision publish를 요청해야 한다.

AI schema는 optional RAG 승인 후 사용한다. MVP에서는 FastAPI가 직접 DB에 쓰지 않고 Spring이 필요한 read projection을 제공한다. AI DB credential을 분리할 때는 별도 ADR과 migration 계획이 필요하다.
