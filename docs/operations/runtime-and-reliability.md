# Runtime and reliability

2026-09-11 개선안. 다음 값은 단일 Spring 인스턴스·작은 pilot 대상의 **초기 상한 설정**이며 실제 무료 상품이 이를 제공한다는 뜻이 아니다. 호스팅 memory/DB limit을 확인해 더 낮출 수 있고, 부하 시험 전 처리량을 보장하지 않는다.

## HTTP, SSE, snapshot 복구를 기능에 맞춰 구분한다

지도 목록은 일반 HTTPS GET/JSON, 작성/좋아요는 짧은 command다. 여정은 POST→202→SSE progress/terminal event→GET snapshot이다. SSE는 진행 알림이며 DB run/snapshot이 상태 정답이다. FastAPI도 내부 POST/JSON 한 응답이다. HTTPS는 전송 보안이며 SSE와 경쟁하는 프로토콜이 아니다. HTTP keep-alive/HTTP2 연결 재사용은 client/proxy가 처리하므로 요청마다 새 TLS socket을 강제하지 않는다.

Spring MVC blocking 요청 thread는 짧은 인증·DB 작업 뒤 반환한다. 20초 run 동안 servlet thread나 DB transaction을 유지하지 않는다. AI 호출은 별도 제한 executor에서 수행하고 응답 저장 때만 DB connection을 빌린다. Open Session In View는 off. 지도의 새 요청은 기존 servlet pool로 처리하며 사용자마다 thread/executor를 생성하지 않는다.

여정 SSE는 MVP에 채택한다. actor당 stream1, 전체20, heartbeat15초, 연결 최대60초 후 client 재연결을 시작값으로 둔다. event payload는 stage/terminal/heartbeat/reset만 포함하며 board나 개인정보를 보내지 않는다. run별 단조 sequence와 작은 in-memory replay buffer를 유지하되, Last-Event-ID 공백·배포·재시작은 `reset` 후 GET run/snapshot 재동기화로 해결한다. DB에 event log를 추가하지 않으며 SSE replay가 정확한 업무 이력을 제공한다고 주장하지 않는다. bounded send buffer를 넘긴 느린 consumer, 권한 만료, terminal 전송 뒤 연결은 닫는다. proxy buffering/idle timeout, heartbeat, reconnect와 GET 복구는 FE/BE 통합 fixture로 검증한다. EventSource 미지원 환경에만 polling fallback을 제공한다. MVC async는 servlet thread를 반환할 수 있지만 응답 write/연결/메모리 비용은 남는다([Spring MVC async](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)).

## 통합 초기 예산

| 자원 | 상한 / 설정 의도 |
|---|---|
| Servlet | platform thread max32, min spare4, connection128, accept backlog32; ingress body16KiB |
| AI executor | core=max2, queueCapacity=2, AbortPolicy; CallerRunsPolicy 금지 |
| DB global active runs | QUEUED+RUNNING<=4, actor당1, exploration당1; DB admission row 잠금으로 경쟁 제어 |
| FastAPI | process1, concurrent workflow2, upstream HTTP pool2; CPU 모델 로컬 load 안 함 |
| Hikari Spring | max6,minIdle1, acquire timeout500ms; query statement timeout2s, lock timeout300ms |
| DB 예산 식 | Spring6+AI DB0+sync별도pool0+운영/마이그레이션2+reserve2=10 이하; 실제 limit>=10 확인 필요 |
| Scheduler / sync | scheduler2(short cleanup/trigger), sync executor1, queue0; Spring Hikari 공유, batch100 이하 |
| HTTP clients | AI max2/connect500ms; tourism max1/connect1s; Kakao max2/connect1s |
| actor run 생성 | guest5/10분, member10/10분; IP30/10분; 먼저 도달한 제한 적용 |
| 공개 GET | IP120/분, burst20; snapshot GET actor120/분; SSE actor당1/전체20; client-controlled XFF 신뢰 금지 |
| 후기 작성/좋아요 | member 후기5/시간, 좋아요 변경60/분; IP제한 병행 |
| 모델 | 기본 external calls0, Gemini 무료 티어는 명시적 opt-in과 별도 일 call/token cap 승인 후에만 활성화 |

단일 replica이므로 정확 admission은 DB 잠금/카운터와 partial unique로 구현한다. 분/10분 window는 DB-clock 고정 window, boundary burst는 global active cap이 제어한다. actor/global/IP counter 갱신과 run insert를 한 짧은 transaction으로 묶는다. 이전 만료 run terminalize 후 계산하며 명시적 lock order는 admission(global→IP→actor)→member→exploration→run이다. cleanup/completion/cancel도 동일한 global→IP→actor→member→exploration→run 잠금 순서를 사용하고 run 상태와 slot 반환을 같은 transaction으로 갱신한다. 불변 scope key는 잠금 전에 읽을 수 있으나 상태 조건은 잠금 후 재검사한다. 제한 실패 시 run/turn row를 만들지 않고429를 반환한다. local token bucket은 보조 최적화만 허용한다.

평균 AI 8초, worker2면 수용률은 대략 .25 runs/s 미만이다. global4에서 SSE는 연결 최대4개와 heartbeat traffic만 추가하며, terminal/재연결 시 GET snapshot 부하를 별도 측정한다. 과거 동시50 목표는 stress scenario이지 이 상한에서50개의 AI 실행을 허용한다는 뜻이 아니다. N replicas로 늘리면 `N*poolMax+other+reserve<=DB limit`, global limiter, SSE connection cap을 다시 계산한다. Virtual thread도 DB/AI semaphore를 대체하지 않으며 초기에는 사용하지 않는다.

Spring의 [TaskExecutor 설정](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)에 맞춰 core/max/queue/rejection을 명시하고 TaskDecorator로 trace context를 복사·finally 정리한다. 자동 기본 executor나 common ForkJoinPool에 AI 작업을 흘리지 않는다.

## 임시 Gemini 이용 정책

공개 MVP에서 Gemini 무료 티어를 사용할 때, 비회원은 KST 일자당 AI 여정 실행 2회, 로그인 회원은 5회로 제한한다. 이 수치는 제품 가설이며 실제 provider quota, 비용, abuse 지표를 확인한 뒤 GitHub Issue에서 재결정한다. 공개 지도·장소·baseline 탐색은 이 한도로 막지 않는다.

AI 실행은 사용자의 명시적 opt-in 뒤에만 admission한다. member quota는 `identity.members.id`, guest quota는 서버 guest credential을 primary key로 하고 IP budget은 우회 방지 보조로 적용한다. 한도 검사는 global/provider admission과 같은 짧은 DB transaction에서 수행한다. provider 호출을 실제로 dispatch한 run만 1회를 소비하며, admission 거절이나 dispatch 전 내부 실패는 소비하지 않는다.

개인 위치, OAuth/session 정보, 저장 목록, 후기 원문은 Gemini에 보내지 않는다. Spring은 검수된 후보와 제한된 구조화 조건을 준비하고, FastAPI는 그 allowlist 내부의 순서·근거만 제안한다. Gemini timeout/quota/malformed response 또는 FastAPI 내부 장애에는 새 provider call 없이 같은 run의 검증된 후보·template 근거로 `engine=BASELINE`을 확정한다. FE는 raw 장애 문구 대신 기본 탐색 결과임을 표시한다. 지역/주제 해석 또는 후보 검색 실패면 임의 결과를 만들지 않고 clarification/no-results를 반환하며, cancel·정책 거절·Spring 불변식 실패에는 fallback하지 않는다.

## run 수명과 deadline

1. admission transaction에서 createdAt=DB clock, deadlineAt=createdAt+20s, QUEUED/generation1 저장 후 commit. 그 뒤 executor 제출한다.
2. 제출 거절이면 run을 QUEUED→FAILED(QUEUE_REJECTED) CAS하고 slot 반환. 이미202가 전송됐으면 FE는 SSE terminal event 또는 GET snapshot에서 읽는다. commit 뒤 crash로 제출이 누락되어도 아래 sweeper가 복구한다.
3. worker는 DB CAS로 QUEUED→RUNNING; generation/status/deadline 일치 때만 시작한다. stale queued task는 no-op. QUEUED가2초 넘으면 sweeper가 FAILED(QUEUE_TIMEOUT) 처리하므로 큐가 약속하지 못하는 지연을 숨기지 않는다.
4. queue 포함 절대20초. model/AI 응답 budget은 min(12초, remaining-2초), remaining<=2초면 외부 호출 시작 안 함. connect500ms 포함, 외부 생성 retry0. AI 처리 내부 provider도 전달받은 남은 deadline을 준수한다. 장기 문서의15초+재시도1 정책은 MVP에 적용하지 않는다.
5. 검증/완료 transaction에서 member ACTIVE/guest 유효, exploration not deleted, baseVersion, run RUNNING/generation, DB now<deadline을 모두 확인한다. board/proposal 저장과 run COMPLETED/slot 반환은 원자적이다. 만료·cancel·deletion이 먼저면 결과 폐기.
6. startup과 매1초 sweeper가 QUEUED age>2초, RUNNING now>=deadline을 각각 FAILED로 terminalize한다. run GET도 만료 조건부 갱신 후 읽어 cold restart/cleanup 지연을 보완한다. batch100, DB down이면503/readiness false, 복원 후 재수행한다.
7. terminal run을 재실행하지 않는다. 사용자 retry는 새run과 key; 동일 key는 기존 실패를 반환한다. cancel은 terminal CAS, worker interrupt/HTTP cancel best effort. 물리 호출이 종료되기 전에는 executor slot이 실제로 남지 않는다는 점을 포화 테스트한다.

## 수집 LKG는 dataset revision 단위로 게시한다

[원천별 스키마와03:00 KST 일정·수면 복구](../spring/catalog-ingestion.md)를 후속안으로 추가한다. 증분 revision도 기존 전체 집합에 delta를 적용한 완전한 공개 집합이어야 한다. 아래 lease/fence/LKG 원칙을 모든 수집 dataset에 적용한다.

Canonical stable ID와 versioned 공개 행을 분리한다. `catalog.place_identity(id PK)`는 안정 ID이고 여러 source는 `place_sources(place_id FK,provider,dataset,external_id,language,UNIQUE(provider,dataset,external_id,language))`로 매핑한다. `dataset_revisions(id,dataset,status,sourceObservedAt,fetchedAt,publishedAt)`, `place_versions(revision_id,place_id,normalized fields,hash)` 복합PK, `active_datasets(dataset PK,revision_id FK)`를 둔다. 기존places 수정형 모델은 이 revision 모델의 current view로 대체한다. source별 여러 dataset 조합은 response의 datasetRevisions로 노출하고 전국 모든 원천의 동시 일관성을 약속하지 않는다.

Sync는 `operations.sync_leases(dataset PK,owner_token,generation,lease_until)`을 DB clock으로 획득한다. lease30초, heartbeat10초; 재획득마다 generation+1. 모든 stage write와 publish transaction은 lease row를 잠그고 owner/generation/expiry 검증 후 수행한다. 외부 호출 중 lease row 잠금 금지. heartbeat 실패/만료면 즉시 중지하고 늦은 worker 쓰기를 거절한다.

Page를 private revision에 batch insert한다. 전체 page count/중복 key/원천 오류 envelope/형식/좌표 검증이 끝나기 전 active pointer를 변경하지 않는다. 필수행 quarantine이 발생하면 run 실패, 이전 revision 유지; 자발적 이미지 등 optional 누락만 null로 게시 가능. publish는 lease 재검증→revision READY→active pointer CAS→SUCCEEDED/watermark를 단일 transaction으로 처리한다. 독자는 시작 시 revision ID 하나를 잡아 같은 revision을 hydrate한다. 이전 revision은 최소7일, active/진행중 run 참조 revision은 추가 보존한다. Saved는 자체 snapshot이므로 원본revision 물리FK에 매달리지 않는다.

삭제: 명시 tombstone은 성공한 게시에 포함; full pagination 2회 연속 성공에서 미관측된 key만 삭제 후보로 확정한다. 실패/빈 오류 응답을 전체삭제로 해석하지 않는다. incremental은 최근 watermark-48h overlap, (sourceModifiedAt,externalId) tie 및 normalized hash로 비교한다. 공급자가 안정 cursor/snapshot을 제공하지 않으면 완전 무손실을 주장하지 않고 주간full reconcile을 수행한다.

Tourism HTTP는 connect1s, attempt 전체5s, retry 최대2회이되 page 전체budget12s, jitter 200..500ms, 남은budget 부족하면 중지. 429 Retry-After가 budget 밖이면 다음 sync로 미룬다. non-idempotent 작업은 자동 retry없음. 페이지 순서/중복/object-list-null/200 error를 provider별 adapter에서 검증한다.

| dataset | 갱신 / stale 표시 / 제공 상한 |
|---|---|
| 장소명·좌표·검수 설명 | 일1회, 성공후36h 경고, 7일 초과도 stale 표시 LKG 조회 허용하되 신규 AI 추천 근거에서는 제외 |
| 운영시간·가격·접근성 | 24h 초과 미확인/null, 신선한 측정처럼 노출 금지 |
| 일별 지역 관측 | basisDate와 공간단위 표시, 72h 초과 시 현재 지표 block 숨김; 역사 조회는 허용 |
| 후기 | 사용자 DB commit 즉시 공개; 장소 폐기/회원삭제/신고 moderation에 따라 숨김 |

sourceObservedAt(원천 기준), fetchedAt(수집), publishedAt(게시), basisDate(통계대상)를 구분한다. stale reason과 age를 내려 FE가 빈 결과로 바꾸지 않게 한다. LKG는 DB 장애를 해결하지 않는다.

## 환경·복구·관측과 출시

설정은 DATA_MODE=FIXTURE/VERIFIED_SNAPSHOT/LIVE_CANONICAL, ENGINE=BASELINE/LLM, APP_ENV, AUTH_MODE로 분리한다. prod/staging은 LIVE_CANONICAL+BASELINE 또는 LLM 허용; LLM은 budget/평가 통과 필요. demo는 VERIFIED_SNAPSHOT+BASELINE+KAKAO_TEST_APP, test는 FIXTURE와 test principal만 허용한다. execution.engine/dataMode/datasetRevision을 응답에 표시하고 제품 지표는 실제 데이터 기준 engine별로 나눈다. demo 결과는 합산하지 않는다.

백업 기본안: 매일 암호화 full logical backup, 7일 retention, app DB와 다른 storage/credential, 운영담당 role만 decrypt. provider PITR는 지원 검증 후 선택. 회원 첫 실사용 write 이전 별도DB 복원 drill이 필수다. 순서: write 중지→새DB 생성→schema+data 복원→삭제ledger 재적용→FK/count/saved hash·회원별 negative test→호환 앱 연결→QUEUED/RUNNING 만료→readiness/쓰기 재개. 목표 RPO24h/RTO4h를 실제 시각으로 측정하고 초과면 공개 write 보류. 자동 destructive down migration 금지, expand/contract와 N/N-1 앱 rollback 가능 범위를 release별로 검증한다. 호스팅/backup destination/담당자 미확정은 남은 공개 게이트다.

Release Please 유지. 구현 정책은 main SHA checkout→동일 hygiene/추가계약검증 verify job→needs:verify release job, release job만 contents/pull-requests/필요issues write를 갖게 한다. CI 실패 SHA에는 tag/release0이 acceptance다. 현재 workflow 파일은 이 문서 작업에서 수정하지 않았으므로 F13 실제 결함은 열려 있다. merge 전 approval/CI, main만tag 정책 유지.

## Grafana Cloud 관측성과 경보

관측의 정답은 자체 incident queue가 아니라 **Grafana Cloud**다. Spring Boot는 Actuator와 Micrometer Observation/Tracing으로, FastAPI는 OpenTelemetry SDK로 metrics, logs, traces를 OTLP로 전송한다. Grafana Cloud의 dashboard, alert rule, alert history가 미해결 경보와 조사 이력의 기준이며 Discord와 email은 전달 채널일 뿐 상태 저장소가 아니다. 자체 Prometheus, Loki, Tempo, Alertmanager cluster는 MVP에 도입하지 않는다.

두 서비스는 다음 resource attribute를 동일하게 보낸다: `service.name`, `service.version`, `deployment.environment`, `trace_id`, `request_id`, `run_id`(존재할 때만). `traceparent`는 browser Spring request부터 executor, Spring-to-FastAPI internal HTTP까지 전파한다. requestId는 HTTP 응답 진단 ID, traceId는 분산 상관관계, runId는 업무 실행 ID다. JSON log에는 timestamp, level, service, operation, requestId, traceId, runId, errorCode, durationMs만 allowlist한다. actor, query, 정확 위치, cookie, service token, evidence body는 로그와 metric label에 넣지 않는다. metric label에 runId/placeId/userId도 넣지 않는다.

### 최소 dashboard와 signal

| Dashboard | 핵심 신호 | 조사 시작점 |
|---|---|---|
| Service health | HTTP request/error/latency, JVM, readiness, DB pool wait | 5xx 또는 p95 급등 trace |
| AI journey | run stage/terminal, provider latency/failure, quota/admission, SSE reset/reconnect | runId로 Spring-FastAPI trace와 JSON log 연결 |
| Data and RAG | sync age/failure, published revision, corpus active revision, manifest ACK lag | revision/manifest hash와 sync run log |
| Public UGC | report created/open age, moderation action, high-risk auto-hide | report ID가 아닌 aggregate와 operator audit event |

실제 metric 이름은 구현 시 Micrometer/OpenTelemetry semantic convention과 충돌하지 않게 정하되, `onmaru.ai.run`, `onmaru.ai.provider`, `onmaru.sse`, `onmaru.corpus`, `onmaru.moderation` namespace 아래 low-cardinality outcome/errorCode/environment label만 허용한다. trace와 log는 Grafana에서 상호 이동 가능해야 하며, production query나 개인정보를 dashboard annotation에 넣지 않는다.

### Alert와 통지 정책

| Severity | 초기 rule | Grafana 대응 | 통지 |
|---|---|---|---|
| warning | 5분 request>=20이고 5xx>5%, SSE reset/reconnect 비율 급증, provider failure 증가, corpus ACK lag, sync age>36h | dashboard와 runbook으로 조사, 다음 업무 시간 triage | Discord |
| critical | RUNNING age>21초 지속, readiness false, DB pool wait p95>300ms 5분, backup age>26h, AI provider 전체 실패, high-risk PII moderation event | 즉시 runbook 수행, 필요 시 AI admission/공개 write 중지 또는 engine baseline 전환 | Discord와 email 동시 |

각 alert는 `environment`, `service`, `errorCode` 또는 집계 revision, Grafana dashboard URL, 해당 runbook URL을 annotation으로 가진다. Discord webhook은 Grafana contact point로 등록하되 유일한 수신자로 사용하지 않는다. critical rule은 Discord와 email contact point 모두에 연결하고, staging에서 각 severity별 test notification과 resolve notification을 확인해야 한다. 담당자, 수신 email, Discord webhook, Grafana Cloud stack/OTLP credential은 secret manager와 배포 설정에서만 관리하며 문서나 browser에 노출하지 않는다.

AI provider 장애는 같은 run에서 숨은 **provider 재시도**를 하지 않는다. Spring은 이미 만든 후보를 사용해 `BASELINE` terminal/snapshot을 확정하고 execution.engine에 결과를 표시한다. 후보가 없거나 Spring의 policy/invariant가 실패한 경우에만 해당 typed terminal outcome을 반환하고 기존 board를 보존한다. correlation signal은 향후 read-only 운영 보조 agent가 provider outage, quota surge, corpus drift를 분류하고 runbook을 제안하는 입력으로만 사용한다. 그 agent에는 DB 변경, credential 읽기, alert close, provider 호출 권한을 주지 않는다.

FE 계약 source는 향후 저장소 내부 version-pinned snapshot+manifest(source repo/commit/path/SHA256)로 전환 제안한다. 새 clone에서 hash 동일성을 CI로 확인하고 기존 symlink 존재검사는 그 시점에 교체한다. 현재 symlink를 삭제/원본 수정하지 않았으므로 F21도 완료 처리하지 않는다.

LLM 실패 시 같은 run에서 추가 생성이나 provider 재시도를 하지 않는다. 이미 검증된 deterministic 후보가 있으면 Spring이 `BASELINE`으로 완료하고, 없으면 FAILED/clarification/no-results를 확정한다. 이 fallback은 숨겨진 모델 재시도가 아니며 execution.engine과 Grafana aggregate에 명시적으로 남긴다.
