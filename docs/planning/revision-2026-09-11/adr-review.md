# 구조적 결정의 ADR 등록 전 승인 자료

2026-09-11. **초안이며 아직 ADR 파일이 아니다.** 현재 accepted ADR은 ADR-0001 프로세스 기록뿐이다. Toolkit preflight는 기존 docs/decisions를 확인했고 related --paths docs/planning docs/database 결과0이었다. 기존 accepted 구조 ADR을 대체하는 요청은 없다.

본 자료는 forward-looking RECORD다. 현재 구현이 없는 사실, 제안, 가정과 검증을 구분한다. 승인 후 status=proposed로 create한다. proposed 등록 승인과 accepted 전환 승인은 다르다. 형식은 MADR full의 문제/driver/대안/결과/장단점/검증/재검토 항목을 따른다.

공통 확인 사실: documentation-first, Java/Spring+경량FastAPI 선택, 여정/카카오저장 우선, 현재polling, 실제성능 미검증. 공통 가정: 단일instance/작은pilot, DB connection budget 확보 가능. 미확인: 호스팅/backup/모델budget/운영책임자. 아래 숫자는 significance이지 시스템 품질점수가 아니다.

## 소비자 소유 port와 제한된 bridge로 Spring 모듈 경계를 보호한다

- **Slug:** `module-boundaries`
- **문제:** 최신 identity/discovery/journey 책임이 기존 hexagonal 표와 연결되지 않아 compile/runtime cycle 위험이 남는다.
- **확인 근거:** [architecture.md](architecture.md), 원본 감사 관련 F01–F21.
- **결정 제안:** 선택적 헥사고날·순수 core와 app bridge DAG.
- **주요 driver:** 변경 영향과 순환 의존을 제한하면서 MVP 파일 수를 억제.
- **감수할 단점:** DTO mapping과 app orchestration 결합이 남고 DB 기술 모듈이 여러core를 볼 수 있다.
- **영향 경로:** `docs/planning/architecture-blueprint.md`, `docs/planning/revision-2026-09-11/`, 향후 해당 core/adapter source.
- **검증:** core classpath/import 및 runtime bridge 호출방향 검증. 현재 runtime 증거 없음.
- **재검토:** core별 독립 배포·소유팀이 필요하거나 persistence package 규칙이 반복적으로 깨질 때.
- **Significance:** 13/14; [7개 점수 입력](adr-scores/module-boundaries.json).

### 검토 대안과 절충

1. **순수 core+기술adapter**: 장점 — JDK core classpath를 보호한다. 단점 — bridge/mapper가 필요하다.

2. **단일feature package**: 장점 — 가장 적은 build 파일로 빠르게 구현한다. 단점 — framework 의존을 compile 단계에서 차단하기 어렵다.

3. **feature별 모든계층 Gradle 분리**: 장점 — 각 feature 계층의 compile 경계를 강제한다. 단점 — 작은 변경에도 모듈/설정 수가 크게 늘어난다.



## 단일 PostgreSQL에서 회원·탐색·저장의 쓰기 소유권을 분리한다

- **Slug:** `postgresql-ownership`
- **문제:** 회원/세션/저장 모델과 DB개수·OAuth grant 경계가 없으면 FE로그인과 저장의 정합성이 달라진다.
- **확인 근거:** [data-and-identity.md](data-and-identity.md), 원본 감사 관련 F01–F21.
- **결정 제안:** 환경당 PostgreSQL/PostGIS 1개와 Spring 세션·단기guest grant·불변 saved snapshot.
- **주요 driver:** 공간조회와 로컬 ACID 저장을 낮은 운영비로 연결.
- **감수할 단점:** 단일DB 장애와 cross-context transaction 결합, backup/삭제 운영 부담.
- **영향 경로:** `docs/database/schema.md`, `docs/planning/revision-2026-09-11/`, 향후 해당 core/adapter source.
- **검증:** 동시login/save, 타인ID, grant만료, 탈퇴late-result, 새DB복원 검증. 현재 runtime 증거 없음.
- **재검토:** DB connection/공간query 병목 또는 규제·배포 격리가 실측 요구될 때.
- **Significance:** 14/14; [7개 점수 입력](adr-scores/postgresql-ownership.json).

### 검토 대안과 절충

1. **단일PostgreSQL+schema**: 장점 — 공간검색·FK·저장 transaction을 한 엔진에서 처리한다. 단점 — 단일DB 장애와 pool경합을 공유한다.

2. **MySQL+공간검색**: 장점 — 팀의 MySQL 경험을 활용할 수 있다. 단점 — 현재 PostGIS 설계를 다시 검증하고 vector 옵션을 재선정해야 한다.

3. **별도identity/vector 서비스**: 장점 — 독립 확장과 자원 격리가 쉽다. 단점 — 인증이관/저장에 네트워크 정합성과 추가 운영비가 생긴다.



## REST와 DB run lifecycle로 지도·여정 실행을 분리한다

- **Slug:** `rest-polling-run-lifecycle`
- **문제:** 연결방식 혼재와 QUEUED 고착·늦은완료·무료자원 초과를 FE와 서버가 함께 처리해야 한다.
- **확인 근거:** [runtime-and-operations.md](runtime-and-operations.md), 원본 감사 관련 F01–F21.
- **결정 제안:** 지도짧은REST, 여정202+polling, deadline/CAS/DB admission.
- **주요 driver:** 복구 가능한 snapshot과 bounded 자원으로 단일인스턴스 운영.
- **감수할 단점:** polling 요청부하와 늦은진행표시, 포화시429 및 queued timeout.
- **영향 경로:** `docs/planning/journey-exploration/seven-day-mvp-fe-handoff.md`, `docs/planning/revision-2026-09-11/`, 향후 해당 core/adapter source.
- **검증:** commit-dispatch kill, cancellation race, deadline, saturation 및 FE stale response 테스트. 현재 runtime 증거 없음.
- **재검토:** polling DB 비용/진행지연이 측정된 병목이거나 동시편집 요구가 확정될 때.
- **Significance:** 14/14; [7개 점수 입력](adr-scores/rest-polling-run-lifecycle.json).

### 검토 대안과 절충

1. **REST+polling**: 장점 — snapshot 재조회와 proxy 복구가 단순하다. 단점 — 주기 GET 비용과 최대 polling 간격의 표시지연이 있다.

2. **SSE+durable events**: 장점 — 진행변화를 즉시 단방향 push한다. 단점 — replay/event보존/권한만료/느린consumer 처리가 추가된다.

3. **WebSocket**: 장점 — 양방향 실시간 협업에 맞다. 단점 — 현재 필요 없는 연결상태·재접속·메시지 프로토콜을 운영해야 한다.



## fenced dataset revision으로 마지막 정상 데이터를 원자적으로 게시한다

- **Slug:** `atomic-dataset-publication`
- **문제:** page upsert와 LKG 약속이 충돌하고 만료worker가 신규게시를 덮을 수 있다.
- **확인 근거:** [runtime-and-operations.md](runtime-and-operations.md), 원본 감사 관련 F01–F21.
- **결정 제안:** private staging revision + active pointer + DB-clock generation fence.
- **주요 driver:** 부분수집 실패와 stale worker가 공개데이터를 변경하지 못하게 함.
- **감수할 단점:** revision 저장공간·GC·dataset 사이 mixed revision 관리 비용.
- **영향 경로:** `docs/planning/data-api-design.md`, `docs/planning/revision-2026-09-11/`, 향후 해당 core/adapter source.
- **검증:** 마지막page실패, A만료→B게시→A도착, tombstone/GC 참조검증. 현재 runtime 증거 없음.
- **재검토:** revision 저장공간/게시transaction시간이 측정된 예산을 넘을 때.
- **Significance:** 14/14; [7개 점수 입력](adr-scores/atomic-dataset-publication.json).

### 검토 대안과 절충

1. **revision pointer**: 장점 — 완성된 dataset만 O(1) pointer 전환으로 공개한다. 단점 — 이전 revision 저장/GC 비용이 든다.

2. **row별 LKG/mixed version 허용**: 장점 — 변경row만 저장해 공간을 절약한다. 단점 — 부분 성공의 혼합version을 FE와 추천에서 허용해야 한다.

3. **full table swap**: 장점 — 완성된 staging table 전체를 교체할 수 있다. 단점 — FK/view/index/동시조회와 DDL잠금 운영이 복잡해진다.



## 실제 데이터 baseline을 기본으로 두고 평가 후 RAG를 활성화한다

- **Slug:** `baseline-optional-rag`
- **문제:** MVP optional model과 prod모델강제 조건이 충돌하며 순위/청킹 수치의 근거가 없다.
- **확인 근거:** [retrieval.md](retrieval.md), 원본 감사 관련 F01–F21.
- **결정 제안:** LIVE_CANONICAL+BASELINE 허용, hard-filter ranking, optional RAG 평가gate.
- **주요 driver:** 비용과 사실근거를 통제하면서 품질개선을 측정 가능하게 함.
- **감수할 단점:** 초기 의미검색 한계와 검수사전·held-out 평가 유지 비용.
- **영향 경로:** `docs/planning/journey-exploration/staging-demo-release-and-success-gates.md`, `docs/planning/revision-2026-09-11/`, 향후 해당 core/adapter source.
- **검증:** 동일dataset baseline 대조, 허용ID/pin0위반, recall/nDCG/claim/cost 검증. 현재 runtime 증거 없음.
- **재검토:** corpus가context한도를 넘고 held-out 개선이 예산 내에서 검증될 때.
- **Significance:** 13/14; [7개 점수 입력](adr-scores/baseline-optional-rag.json).

### 검토 대안과 절충

1. **metadata baseline**: 장점 — 추론 비용 없이 검수근거를 재현한다. 단점 — 사전 밖 자연어 의미검색이 약하다.

2. **bounded 후보LLM**: 장점 — 새vector 저장소 없이 연결이유를 유연하게 작성한다. 단점 — allowlist 밖 recall은 개선하지 못하고 모델비용이 든다.

3. **pgvector hybrid RAG**: 장점 — 큰 corpus에서 의미 근거를 회수할 수 있다. 단점 — 청킹/색인 동기화/평가/추론 운영비가 추가된다.



## Implementation Constraints 초안

module-boundaries ADR에는 다음 검사 가능한 규칙을 넣는다. 미래 source가 없으므로 현재 통과 여부로 아키텍처 준수를 주장하지 않는다. 나머지 runtime 불변식은 contract/DB 테스트로 검증하며 toolkit이 semantic 검증한다고 오해하지 않는다.

```yaml
constraints:
  - id: core-no-framework-imports
    kind: forbidden_import
    paths: ["modules/*/src/main/java/**"]
    pattern: ["import org\\.springframework", "import jakarta\\.persistence", "import reactor\\."]
    severity: major
    message: "Core must not import Spring, persistence, or Reactor types."
```

단순 API 필드/좋아요 endpoint/가중치 소수점마다 ADR을 만들지 않는다. 이들은 승인된 경계 안에서 API 및 rankingVersion으로 추적한다. 각 초안 등록에 대한 승인을 받은 뒤 toolkit create/validate/index를 수행한다. 현재 docs/decisions의 생성·상태 변경은 하지 않았다.
