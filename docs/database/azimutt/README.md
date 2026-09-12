# Azimutt import files

이 디렉터리는 OnMaru DBML ERD를 Azimutt에서 무료로 탐색하기 위한 import 파일을 둔다. 원본은 `docs/database/schema.dbml`과 `docs/database/modules/*.dbml`이며, 이 디렉터리 파일은 생성 산출물이다.

## 왜 Azimutt인가

Azimutt는 큰 실제 스키마에서 필요한 테이블과 관계를 빠르게 탐색하고 필요한 subset view를 만드는 데 강한 ERD 도구다. OnMaru처럼 Identity, Catalog, Audio, Discovery, Journey, Community, Operations, AI처럼 모듈이 나뉘고 테이블이 계속 늘어날 수 있는 설계에는 전체 ERD 한 장보다 prefix 기반 view를 만든 뒤 PNG로 export해서 GitHub에 공유하는 방식이 더 적합하다.

## import에 사용할 파일

Azimutt에서는 아래 strict SQL 파일을 먼저 import한다.

```text
docs/database/azimutt/onmaru-schema.azimutt-strict.sql
```

이 파일은 DBML에서 변환한 PostgreSQL DDL에서 Azimutt parser가 경고를 내기 쉬운 enum, uuid, timestamptz, jsonb, PostGIS, vector, 별도 index 문을 낮춘 파일이다. 운영 migration 기준으로 쓰지 않는다.

`onmaru-schema.azimutt.sql`은 PostgreSQL 표현을 조금 더 보존한 보조 import 파일이다. 에러가 없으면 strict 파일만 사용한다.

원본 PostgreSQL 변환 결과를 확인하려면 아래 파일을 본다.

```text
docs/database/azimutt/onmaru-schema.postgres.sql
```

## 사용 순서

1. <https://azimutt.app/>에 접속한다.
2. 새 project를 만든다.
3. SQL import 또는 PostgreSQL SQL import를 선택한다.
4. `docs/database/azimutt/onmaru-schema.azimutt-strict.sql` 내용을 붙여넣거나 파일을 업로드한다.
5. 테이블 검색에서 아래 prefix를 기준으로 focus view를 만든다.
6. 각 view를 PNG로 export해서 `docs/database/azimutt/exports/`에 저장한다.

| Module | Table prefix | 우선 볼 테이블 |
|---|---|---|
| Identity / Auth | `identity_` | `identity_members`, `identity_external_accounts`, `identity_guests` |
| Catalog / KTO Korean | `catalog_` | `catalog_place_identity`, `catalog_place_versions`, `catalog_kto_korean_content_versions`, `catalog_regions` |
| Audio / Odii | `audio_` | `audio_odii_spots`, `audio_odii_stories`, `audio_place_odii_links` |
| Insights / Statistics | `insights_` | `insights_visitor_observations`, `insights_tourism_targets` |
| Discovery / Search | `discovery_` | `discovery_explorations`, `discovery_runs`, `discovery_proposals` |
| Journey / Saved Timeline | `journey_` | `journey_saved_journeys`, `journey_saved_resources` |
| Community Reviews | `community_` | `community_visit_reviews`, `community_review_likes` |
| Operations / Sync | `operations_` | `operations_sync_schedules`, `operations_sync_runs`, `operations_sync_checkpoints` |
| AI / Optional RAG | `ai_` | `ai_documents`, `ai_chunks`, `ai_embeddings` |

## 추천 view

Azimutt에서 모든 테이블을 한 번에 정리하려고 하지 말고, 아래 view를 따로 만든다.

| View | 포함할 prefix / table | 목적 |
|---|---|---|
| `01-system-overview` | `identity_members`, `catalog_place_identity`, `catalog_regions`, `discovery_explorations`, `journey_saved_journeys`, `community_visit_reviews`, `operations_sync_runs`, `ai_documents` | 전체 bounded context와 cross-module FK 확인 |
| `02-identity-auth` | `identity_` | OAuth provider 확장성과 내부 member id 독립성 확인 |
| `03-catalog-kto` | `catalog_` | 한국관광공사 국문 수집, 장소 canonical identity, 지역 코드, dataset revision 확인 |
| `04-discovery-journey` | `discovery_`, `journey_`, 핵심 `catalog_` table | 자연어 탐색, 저장 여정, 월간 타임라인 흐름 확인 |
| `05-map-community` | `community_`, `catalog_regions`, `catalog_place_identity` | 지역별 지도 후기, 좋아요, 장소 연결 확인 |
| `06-operations-sync` | `operations_`, `catalog_dataset_revisions` | 새벽 3시 수집, checkpoint, lease, quarantine 복구 흐름 확인 |
| `07-ai-rag` | `ai_`, 필요한 `catalog_` table | optional RAG corpus/chunk/embedding 경계 확인 |


## GitHub 공유용 PNG

Azimutt에서 배치한 view는 아래 위치에 PNG로 저장한다. 이 이미지를 PR 설명, 설계 리뷰, GitHub markdown에서 바로 보여주는 기본 자료로 사용한다.

```text
docs/database/azimutt/exports/
```

권장 파일명은 다음과 같다.

| File | View |
|---|---|
| `onmaru-erd-system-overview.png` | `01-system-overview` |
| `onmaru-erd-identity-auth.png` | `02-identity-auth` |
| `onmaru-erd-catalog-kto.png` | `03-catalog-kto` |
| `onmaru-erd-discovery-journey.png` | `04-discovery-journey` |
| `onmaru-erd-map-community.png` | `05-map-community` |
| `onmaru-erd-operations-sync.png` | `06-operations-sync` |
| `onmaru-erd-ai-rag.png` | `07-ai-rag` |

## 재생성

DBML을 수정한 뒤 아래 명령을 실행한다.

```bash
node scripts/azimutt-export.mjs
```

그다음 Azimutt project에서 SQL을 다시 import하거나 새 project로 열어 비교한다.
