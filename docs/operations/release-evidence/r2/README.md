# R2 통합 계약 출시 증거

## 게이트 범위

R2 출시 게이트는 `testing/e2e/r2/contract-gate.json`을 기준으로 지도, 방문 후기, Odii, 저장 리소스의 공개 계약을 하나의 Spring runtime 흐름에서 검증한다. 계약 문서와 fixture 자체의 JSON Schema 검증은 `scripts/verify-contracts`가 먼저 수행하고, 같은 명령이 scenario manifest 누락도 차단한다.

| Scenario | Runtime 증거 | 실패 시 차단하는 drift |
| --- | --- | --- |
| `public-region-map-insights` | 비회원 region resolve, map 조회, 관측 heatmap, 결측 지역 | serializer key/type, guest saved state, no-store, 결측의 0 합성, 오래된 관측일 은폐 |
| `member-review-odii-save` | 장소 저장, 후기 작성·좋아요·신고, Odii 저장, actor-bound 저장 목록 cursor | auth/CSRF, saved hydration, raw provider/internal ID 노출, cursor actor 혼용 |
| `moderation-hidden-review` | 운영자 숨김 뒤 공개 후기 목록·지역 집계 재조회 | hidden text와 count 재노출, 비공개 Odii 존재 유출 |
| `source-outage` | map, Odii, 후기 snapshot 장애 | 비표준 500, provider 식별자와 내부 오류 노출 |

## 재현 명령

```bash
python3 scripts/test/validate-r2-e2e-gate.py
bash scripts/verify-contracts
./gradlew :apps:spring-api:test --tests com.yrootlab.onmaru.r2.R2ContractE2ETests --no-daemon --max-workers=1
./gradlew test --no-daemon --max-workers=1
```

PostgreSQL migration 계약은 전체 Gradle 검증의 `apps:spring-api` Testcontainers suite가 함께 실행한다. 고정 provider fixture는 외부 API 호출 없이 동일 입력을 재현하며, runtime suite는 응답을 R2 fixture와 재귀적으로 key/type 비교해 serializer drift를 검출한다.

## 판정 기준

- manifest, OpenAPI, fixture, runtime suite 중 하나라도 없거나 scenario coverage가 빠지면 실패한다.
- 공개 응답에서 `stid`, `stlid`, `serviceKey`, `contentId` 같은 provider key 또는 저장 row ID가 보이면 실패한다.
- moderation 이후 숨김 후기 문장이 공개 목록에 남거나 지역 집계에 포함되면 실패한다.
- source outage가 공통 `SERVICE_UNAVAILABLE`/`retryAfterMs` envelope가 아니면 실패한다.
