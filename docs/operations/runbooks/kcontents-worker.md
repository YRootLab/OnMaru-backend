# K-Contents 검색 근거 worker 운영 (#687)

이 worker는 W5 서버 queue를 HTTPS로 lease하고 짧은 검색 근거만 제출한다. 운영 DB에 연결하지 않는다. n8n이 꺼져도 queue는 서버에 남고, lease 도중 worker가 종료되면 15분 만료 후 서버가 재시도한다. 이 worker의 결과는 `UNCERTAIN` 또는 `NO_MATCH` 원시 조사 결과이며 공개 촬영 관계를 만들거나 기존 관계를 삭제하지 않는다. W7 검증과 게시 승인이 뒤따른다.

## 준비와 기본 실행

검색 provider의 약관, quota, key 위치가 확정되기 전에는 fixture 모드만 사용한다. 예시 JSON은 `workers/kcontents/search/fixtures/pilot-example.json`이다. 서버의 `onmaru.kcontents.research.enabled`도 기본 false이며 관리자 측에서 큐를 채운 뒤 테스트 환경에서만 켠다.

staging에서는 #691 rollout이 **서버 컨테이너**에 `ONMARU_KCONTENTS_RESEARCH_ENABLED=true`와 `ONMARU_KCONTENTS_RESEARCH_WORKER_TOKEN`을 비밀 주입으로 설정한 뒤 배포해야 lease endpoint가 열린다. 현재 staging compose에는 두 항목이 없으므로 이 PR만 배포해도 worker API가 열리지 않는다. 토큰 값은 서버와 로컬 worker에 같은 전용 credential로 각각 주입한다. 관리자 JWT, TourAPI key, DB 암호를 worker 토큰으로 재사용하지 않는다. **로컬 worker 호스트**의 `ONMARU_RESEARCH_API_BASE`는 staging HTTPS API origin만 지정하고 `/api/v1/...` 경로는 붙이지 않는다. 아래 명령의 host 자리만 실제 staging 주소로 바꾼다. `ONMARU_RESEARCH_WORKER_TOKEN`은 서버의 `ONMARU_KCONTENTS_RESEARCH_WORKER_TOKEN`과 같은 값이지만 로컬 전용 비밀 저장소에서 읽는다. 검색 provider key는 별개인 로컬 `ONMARU_SEARCH_PROVIDER_KEY`이며 fixture에서는 불필요하다.

```sh
export ONMARU_RESEARCH_API_BASE='https://<staging-api-host>'
export ONMARU_RESEARCH_WORKER_ID='local-search-1'
export ONMARU_RESEARCH_WORKER_TOKEN='<secret from local secret store>'
export ONMARU_SEARCH_EVIDENCE_HOSTS='example.org'
python3 -m workers.kcontents.search.worker \
  --fixture workers/kcontents/search/fixtures/pilot-example.json \
  --max-jobs 1
```

비밀값은 환경 변수나 로컬 비밀 저장소에서 읽고 명령 인수, 로그, fixture, n8n workflow JSON에 넣지 않는다. `ONMARU_SEARCH_PROVIDER=fixture`가 기본값이다. HTTP provider는 `ONMARU_SEARCH_PROVIDER=http-json`, `ONMARU_SEARCH_LIVE_ENABLED=true`, 승인된 HTTPS `ONMARU_SEARCH_PROVIDER_ENDPOINT`, **별도 외부 allowlist** `ONMARU_SEARCH_PROVIDER_HOSTS`, `ONMARU_SEARCH_PROVIDER_KEY`, `ONMARU_SEARCH_EVIDENCE_HOSTS`를 모두 설정해야만 켜진다. 현재는 검색 제공자/키/이용 약관이 미확정이므로 운영에서 켜지 않는다. provider 연결은 공개 IP를 검증한 뒤 그 IP로 직접 TLS 연결하고 승인된 hostname의 인증서와 SNI를 확인한다. 환경 proxy와 리다이렉트는 사용하지 않는다. 근거 URL은 DNS/allowlist 검증만 하며 페이지 fetch는 하지 않는다.

`workers/kcontents/search/n8n-workflow.example.json`은 **비활성** n8n 템플릿이다. n8n 호스트가 저장소와 Python 3을 볼 수 있게 구성한 뒤, one-shot 명령의 작업 디렉터리와 환경 비밀값을 지정하고 수동 실행으로 먼저 확인한다. n8n에 Execute Command 노드가 허용되지 않는 환경에서는 OS scheduler로 위 one-shot 명령을 호출한다. 동시에 여러 n8n 실행을 켜지 않는다. 서버 lease가 중복 소유를 막지만 호출 예산과 로컬 cache 충돌을 줄이기 위해 한 worker ID당 하나의 프로세스만 운용한다.

## 검색과 복구 경계

- 장소 제목과 지역이 모두 있어야 한다. 기본 쿼리 2개에 지역명을 함께 넣고, 작품 단서가 있을 때만 역방향 쿼리를 추가한다. 지역 없는 동명이인 검색은 실패 처리한다.
- 검색 결과 URL은 http(s), 공개 IP, 허용 host, 기본 port만 통과한다. URL의 추적 파라미터와 fragment를 제거한 canonical URL당 하나만 제출한다. worker는 외부 기사 페이지 본문이나 이미지를 가져오지 않는다. 발췌 최대 1,000자, 한 job당 근거 최대 20개다.
- 출처 등급은 검색 제공자의 주장값을 신뢰하지 않고 기본 `DISCOVERY_ONLY`로 둔다. 검토한 host만 `ONMARU_SEARCH_SOURCE_TIERS='{"official.example":"OFFICIAL"}'`처럼 allowlist와 별도 정책에 등록한다. 가능한 값은 `OFFICIAL`, `BROADCAST`, `CULTURAL`, `PRESS`, `DISCOVERY_ONLY`다. 이 등급 자체가 촬영 관계 검증/자동 공개를 뜻하지 않는다.
- 검색 cache는 로컬 SQLite에 기본 24시간 보존된다. `NO_MATCH`는 동일 장소·조사 이유·source fingerprint에서 기본 30일 후 재조사 대상이다. 제출 JSON의 `nextSearchAt`은 **조사 권장 시각이지 서버 예약 실행이 아니다**. W5에는 `NO_MATCH` 자동 재큐잉 scheduler가 없으므로 운영자가 새 단서/작품 이벤트 또는 만료를 확인해 관리자 requeue를 수행한다. 새 source fingerprint면 TTL 내에도 다시 검색한다. #691 파일럿에서 `NO_MATCH` 후 관리자 재큐잉과 새 fingerprint 재조사를 실증한다. `NO_MATCH`는 촬영 부재 확정이 아니다.
- 429, timeout, 5xx는 `RATE_LIMITED`/`TIMEOUT`으로 `/fail`에 제출한다. 서버가 제한된 backoff와 최대 시도를 관리한다. worker 중단 또는 서버 API 단절 시 lease 만료 후 재실행하며, 이미 저장된 검색 cache를 재사용한다. 실패 시 active 공개 관계는 변하지 않는다.
- 한 one-shot 기본값은 job 1개, job당 검색 최대 3회다. `--max-jobs`는 100을 넘지 못한다. `ONMARU_SEARCH_MAX_CALLS_PER_JOB`, 일일 `ONMARU_SEARCH_MAX_CALLS_PER_DAY`(기본 300), `ONMARU_SEARCH_COST_PER_CALL`, 일일 `ONMARU_SEARCH_MAX_COST`로 호출·추정 비용을 제한한다. 유료 provider를 켤 때는 호출당 비용과 최대 비용을 모두 0보다 크게 명시한다. 호출 **직전** SQLite에 일별 시도/비용을 예약하므로 429·timeout·프로세스 종료와 재기동 후에도 사용한 예산이 복원되지 않는다. cache hit는 새 provider 호출 예산을 쓰지 않는다.

## 100건 파일럿 관찰

100건 제한 파일럿은 #691에서 provider 약관·key가 확정된 뒤 별도로 시행한다. 그때 `--max-jobs 100`과 서버의 하루 lease budget을 함께 제한하고, 기본 `~/.local/state/onmaru/kcontents-search-metrics.jsonl`에서 `jobs`, `jobsWithHits`, `searchCalls`, `cacheHits`, `resultHits`, `failures`, `retries`, `rateLimited`, `timeouts`, `elapsedMs`, `estimatedCost`를 회차별로 집계한다. hit rate는 `jobsWithHits / jobs`, 실패율은 `failures / jobs`로 계산한다. JSONL에는 토큰·URL·검색 원문이 기록되지 않는다. `NO_MATCH` 재조사 시각과 429/timeout 건수를 함께 확인하고 quota 초과 시 worker를 중지한다. SQLite cache/예산 파일도 같은 디렉터리에 둔다.

검증: `python3 -m unittest workers.kcontents.search.test_worker -v`. fixture E2E는 lease→검색→URL 중복 제거→근거 ID 접수→`UNCERTAIN` 제출, 중단 후 SQLite cache 재사용, `NO_MATCH` TTL, 429/timeout `/fail`을 재현한다. 실제 대량 외부 검색은 이 변경에서 실행하지 않는다.
