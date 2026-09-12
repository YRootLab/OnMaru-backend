# 무료 환경의 연결과 실패 복구를 유한한 예산으로 설계한다

2026-09-11 개선안. 다음 값은 단일 Spring 인스턴스·작은 pilot 대상의 **초기 상한 설정**이며 실제 무료 상품이 이를 제공한다는 뜻이 아니다. 호스팅 memory/DB limit을 확인해 더 낮출 수 있고, 부하 시험 전 처리량을 보장하지 않는다.

## HTTP, polling, SSE를 기능에 맞춰 구분한다

지도 목록은 일반 HTTPS GET/JSON, 작성/좋아요는 짧은 command다. 여정은 POST→202→GET run polling→snapshot이다. FastAPI도 내부 POST/JSON 한 응답. HTTPS는 전송 보안이며 SSE와 경쟁하는 프로토콜이 아니다. 향후 SSE도 HTTPS 위에서 동작한다. HTTP keep-alive/HTTP2 연결 재사용은 client/proxy가 처리하므로 요청마다 새 TLS socket을 강제하지 않는다.

Spring MVC blocking 요청 thread는 짧은 인증·DB 작업 뒤 반환한다. 20초 run 동안 servlet thread나 DB transaction을 유지하지 않는다. AI 호출은 별도 제한 executor에서 수행하고 응답 저장 때만 DB connection을 빌린다. Open Session In View는 off. 지도의 새 요청은 기존 servlet pool로 처리하며 사용자마다 thread/executor를 생성하지 않는다.

SSE는 MVP 비활성. 향후 진행 지연이 사용자 이탈의 측정된 원인이고 polling DB 비용이 병목이면 별도 ADR로 검토한다. 초기 후보: actor당 stream1, 전체20, heartbeat15초, connection60초 후 재연결, Last-Event-ID gap이면 snapshot. bounded send buffer와 느린 consumer 종료, expiry/권한 변경 시 연결 종료, event retention/replay, proxy buffering/idle timeout 검증까지 함께 필요하다. 이 값은 미래 실험안이며 현재 FE에 SSE endpoint를 약속하지 않는다. MVC async는 servlet thread를 반환할 수 있지만 응답 write/연결/메모리 비용은 남는다([Spring MVC async](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)).

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
| 공개 GET | IP120/분, burst20; polling actor120/분; client-controlled XFF 신뢰 금지 |
| 후기 작성/좋아요 | member 후기5/시간, 좋아요 변경60/분; IP제한 병행 |
| 모델 | 기본 external calls0, 유료/무료 provider 모두 별도 하루 금액·token/call cap 승인 후 활성화 |

단일 replica이므로 정확 admission은 DB 잠금/카운터와 partial unique로 구현한다. 분/10분 window는 DB-clock 고정 window, boundary burst는 global active cap이 제어한다. actor/global/IP counter 갱신과 run insert를 한 짧은 transaction으로 묶는다. 이전 만료 run terminalize 후 계산하며 명시적 lock order는 admission(global→IP→actor)→member→exploration→run이다. cleanup/completion/cancel도 동일한 global→IP→actor→member→exploration→run 잠금 순서를 사용하고 run 상태와 slot 반환을 같은 transaction으로 갱신한다. 불변 scope key는 잠금 전에 읽을 수 있으나 상태 조건은 잠금 후 재검사한다. 제한 실패 시 run/turn row를 만들지 않고429를 반환한다. local token bucket은 보조 최적화만 허용한다.

평균 AI 8초, worker2면 수용률은 대략 .25 runs/s 미만이다. global4라면 1초 polling은 최대 약4RPS이며 지도/인증은 별도다. 과거 동시50 목표는 stress scenario이지 이 상한에서50개의 AI 실행을 허용한다는 뜻이 아니다. N replicas로 늘리면 `N*poolMax+other+reserve<=DB limit`과 global limiter를 다시 계산한다. Virtual thread도 DB/AI semaphore를 대체하지 않으며 초기에는 사용하지 않는다.

Spring의 [TaskExecutor 설정](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)에 맞춰 core/max/queue/rejection을 명시하고 TaskDecorator로 trace context를 복사·finally 정리한다. 자동 기본 executor나 common ForkJoinPool에 AI 작업을 흘리지 않는다.

## run 수명과 deadline

1. admission transaction에서 createdAt=DB clock, deadlineAt=createdAt+20s, QUEUED/generation1 저장 후 commit. 그 뒤 executor 제출한다.
2. 제출 거절이면 run을 QUEUED→FAILED(QUEUE_REJECTED) CAS하고 slot 반환. 이미202가 전송됐으면 FE는 polling에서 읽는다. commit 뒤 crash로 제출이 누락되어도 아래 sweeper가 복구한다.
3. worker는 DB CAS로 QUEUED→RUNNING; generation/status/deadline 일치 때만 시작한다. stale queued task는 no-op. QUEUED가2초 넘으면 sweeper가 FAILED(QUEUE_TIMEOUT) 처리하므로 큐가 약속하지 못하는 지연을 숨기지 않는다.
4. queue 포함 절대20초. model/AI 응답 budget은 min(12초, remaining-2초), remaining<=2초면 외부 호출 시작 안 함. connect500ms 포함, 외부 생성 retry0. AI 처리 내부 provider도 전달받은 남은 deadline을 준수한다. 장기 문서의15초+재시도1 정책은 MVP에 적용하지 않는다.
5. 검증/완료 transaction에서 member ACTIVE/guest 유효, exploration not deleted, baseVersion, run RUNNING/generation, DB now<deadline을 모두 확인한다. board/proposal 저장과 run COMPLETED/slot 반환은 원자적이다. 만료·cancel·deletion이 먼저면 결과 폐기.
6. startup과 매1초 sweeper가 QUEUED age>2초, RUNNING now>=deadline을 각각 FAILED로 terminalize한다. run GET도 만료 조건부 갱신 후 읽어 cold restart/cleanup 지연을 보완한다. batch100, DB down이면503/readiness false, 복원 후 재수행한다.
7. terminal run을 재실행하지 않는다. 사용자 retry는 새run과 key; 동일 key는 기존 실패를 반환한다. cancel은 terminal CAS, worker interrupt/HTTP cancel best effort. 물리 호출이 종료되기 전에는 executor slot이 실제로 남지 않는다는 점을 포화 테스트한다.

## 수집 LKG는 dataset revision 단위로 게시한다

[원천별 스키마와03:00 KST 일정·수면 복구](regional-map-and-ingestion.md)를 후속안으로 추가한다. 증분 revision도 기존 전체 집합에 delta를 적용한 완전한 공개 집합이어야 한다. 아래 lease/fence/LKG 원칙을 모든 수집 dataset에 적용한다.

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

requestId는 HTTP응답 진단 ID, traceId는 분산 상관관계, runId는 업무 실행 ID다. executor의 traceparent를 FastAPI까지 전달한다. JSON log는 timestamp,level,service,operation,requestId,traceId,runId,errorCode,durationMs만 allowlist; actor/query/정확위치/쿠키 제외. error trace는 내부 제한 로그로 보존한다. metric label에 runId/placeId/userId 금지.

경보 초기안: 5분간 request>=20이고5xx>5%, QUEUED age>3초, RUNNING age>21초, pool wait p95>300ms 5분, sync age>36h, backup age>26h. 대응은 각각 rollback/dependency 점검, sweeper/readiness 점검, admission 감소, sync source/quarantine 점검, backup 재실행·write중지 검토다. 운영 담당자와 수신 채널이 설정되고 시험 알림을 받아야 공개 게이트 통과다. 초기 stdout+관리형 모니터링을 허용하며 자체 관측 클러스터는 요구하지 않는다.

FE 계약 source는 향후 저장소 내부 version-pinned snapshot+manifest(source repo/commit/path/SHA256)로 전환 제안한다. 새 clone에서 hash 동일성을 CI로 확인하고 기존 symlink 존재검사는 그 시점에 교체한다. 현재 symlink를 삭제/원본 수정하지 않았으므로 F21도 완료 처리하지 않는다.

LLM 실패 시 같은 run에서 추가 생성이나 baseline 재실행을 하지 않고 FAILED로 확정한다. BASELINE fallback은 운영자가 engine을 전환하거나 FE의 명시적 재시도로 새 run을 만들 때 선택하며 execution.engine에 표시한다. 따라서 기존 환경 문서의 fallback은 숨겨진 동일run 재시도가 아니다.
