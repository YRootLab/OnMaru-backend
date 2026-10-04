# CI 관측 왕복 증적 (#555)

이 문서는 Toolkit PR #130에서 도입한 Actions evidence → OTLP v1 → Mimir/Tempo → Grafana 계약과 PR #134의 outage range 검증 수정이 포함된 merge commit (`7ecbb89aae771604d9c1c532cf123f239e279110`)을 OnMaruBE의 선택적 후처리에 적용한다. 원본 `evidence.json`과 `diagnostics.md`가 CI 판정의 근거다. Grafana 패널의 값이나 OTLP HTTP 성공만으로 `CI / verify` 결과, Mimir 저장, Tempo 검색 가능성을 판정하지 않는다. 아래 Cloud 항목은 실제 tenant와 완료된 source run을 확인하기 전까지 모두 `pending`이다.

## 증적 기록 형식

실행마다 아래 JSON을 복사해 별도 검증 기록에 채운다. 상태는 `pending`, `pass`, `fail`, `blocked` 중 하나로 쓰고, 관측하지 않은 값은 `null`로 둔다. `pass`에는 검사 시각, 사용한 비밀 없는 쿼리와 확인 가능한 증적 링크를 첨부한다. 토큰, 인증 header, 서명된 artifact URL, 원본 PR artifact 본문은 남기지 않는다. digest는 변경 탐지 값이며 원본 인증 서명이 아니다.

```json
{
  "issue": 555,
  "checked_at_utc": null,
  "consumer_sha": null,
  "toolkit_sha": "7ecbb89aae771604d9c1c532cf123f239e279110",
  "tenant_region": null,
  "source": {"workflow": null, "run_id": null, "attempt": null, "conclusion": null},
  "manifest": {"digest": null, "artifact_url": null, "collection_artifact_url": null, "diagnostic_artifact_url": null},
  "export": {"status": "pending", "reason": null, "acknowledged_batches": null, "pending_batches": null, "diagnostics_url": null},
  "dashboard_url": null,
  "mimir_query_url": null,
  "tempo_trace_url": null,
  "cloud_checks": {
    "credential_scope_and_rotation": {"status": "pending", "evidence_url": null},
    "success_round_trip": {"status": "pending", "evidence_url": null},
    "failure_round_trip": {"status": "pending", "evidence_url": null},
    "outage_and_replay": {"status": "pending", "evidence_url": null},
    "series_and_span_budget": {"status": "pending", "evidence_url": null},
    "retention_and_cost": {"status": "pending", "evidence_url": null}
  },
  "measurements": {
    "active_series": null, "spans_per_run": null, "accepted_trace_bytes_per_day": null,
    "dropped_spans": null, "metric_retention_days": null, "trace_retention_days": null,
    "billing_dpm": null, "month_to_date_cost": null, "approved_monthly_cost_cap": null
  },
  "owner": null,
  "open_gaps": []
}
```

Actions 링크는 `https://github.com/YRootLab/OnMaru-backend/actions/runs/<숫자 run_id>`와 같은 run의 `#artifacts`로 만든다. `ci-observability-collection-<run_id>-<attempt>`와 `ci-observability-diagnostic-<run_id>-<attempt>` artifact에서 `collection.json`, `evidence.json`, `diagnostics.md`, `export-result.json`, `replay.sqlite`의 존재와 digest/attempt를 확인한다. Artifact 보존은 workflow 설정상 14일이므로 장기 감사·replay가 필요하면 접근 제한된 별도 보존소와 기간을 승인받아야 한다. 링크가 유효한 것과 해당 artifact를 실제로 다운로드할 권한이 있는 것은 각각 확인한다.

## 대시보드와 쿼리 계약

[`ci-benchmark.json`](../../../observability/grafana/dashboards/ci-benchmark.json)은 Toolkit v0.1.3의 생성된 대시보드를 그대로 복사한 것이다. 로컬 datasource UID는 `local-prometheus`와 `local-tempo`다. Cloud import 전 실제 Mimir/Tempo datasource UID를 확인하고 **패널 datasource, 각 target datasource, Tempo Explore 링크에 인코딩된 datasource UID를 함께** 바꾼 뒤 가져온다. 보존할 원본의 query 문자열과 단위, 범위, 링크 필드, 고정 `workflow="ci",environment="test"` 및 repository selector를 비교한다. Cloud에서 metric suffix/temporality가 다르면 Toolkit query 계약 변경과 재검증을 별도 작업으로 기록한다. 이 파일을 임의로 수정해 조용히 metric 의미를 바꾸지 않는다. Collector 내부 지표 패널은 로컬 Collector의 `collector-internal` scrape를 전제로 한다. Cloud에 그 scrape가 없거나 Toolkit이 Cloud로 직접 보낸다면 해당 패널은 N/A이며 정상 0으로 해석하지 않는다.

Mimir에서 source artifact와 대조할 대표 쿼리:

```promql
toolkit_ci_job_duration_seconds_sum{workflow="ci",environment="test",ci_job="other"}
toolkit_ci_outcome{workflow="ci",environment="test",scope="job",outcome="failure",ci_job="other"}
toolkit_ci_collection_quality{workflow="ci",environment="test",scope="job",ci_job="other"}
```

현재 OnMaruBE adapter의 CI job catalog는 비어 있어 모든 job metric은 `ci_job="other"`로 집계된다. 위 쿼리는 이 실제 전송 label에 맞춘 전체 job 조회이며 Spring API 단독 selector가 아니다. 개별 job의 이름과 시간은 source artifact와 trace에서 확인한다.

Tempo의 성공·비성공 TraceQL은 대시보드의 두 table target을 그대로 사용한다. Trace의 `cicd.pipeline.run.id`, `toolkit.ci.run.attempt`, `toolkit.ci.manifest.digest`, `cicd.pipeline.result`가 source artifact와 일치해야 한다. `Tempo trace` 링크는 실제 trace를 열고, `Actions run`과 `Manifest artifacts` 링크는 같은 숫자 run ID로 연결돼야 한다. Manifest 링크는 artifact 목록이지 검증된 개별 다운로드 링크가 아니다. 실패·취소는 ERROR만으로 찾지 말고 비성공 table의 result 속성으로 확인한다. workflow span이 없는 untimed evidence는 source diagnostic에서 확인한다.

Duration은 histogram `_sum / _count`의 관측 평균이고 workflow 값은 첫~마지막 유효 job window다. Outcome, quality, work는 마지막 manifest의 gauge snapshot이다. `rate`/`increase`나 누적 실패율로 읽지 않는다. `ci_job`, `module`, `scope`, `outcome`, `quality`, `workflow`, `environment`만 허용된 metric label이다. run ID, attempt, digest, SHA, step/test 이름, 경로, URL을 label로 추가하지 않는다. Source identity는 trace attribute와 artifact에서 찾는다.

## 로컬 성공·실패·중단·replay 검증

다음은 credential과 외부 tenant가 없어도 실행할 수 있다. Node fixture suite는 fake GitHub API와 로컬 HTTP receiver를 사용하며 완료된 source run의 성공·실패·취소, archive 검증, HTTP 실패 시 CI conclusion 보존, 같은 digest replay 억제를 검사한다.

로컬 필수 도구는 Docker Engine과 Docker Compose v2, Python 3.9+, Node.js 22다. macOS에서는 Docker Desktop 하나로 Engine과 Compose를 준비할 수 있다. GitHub Actions를 CLI로 조회할 때만 인증된 `gh`가 추가로 필요하다. Grafana, Prometheus, Tempo, OpenTelemetry Collector는 Compose가 고정 image로 제공하므로 별도 설치가 필요하지 않다.

```bash
node --test scripts/test/grafana-ci-observability.test.mjs scripts/test/ci-observability-workflow.test.mjs
```

Toolkit checkout을 위 SHA에 고정하고 Toolkit 디렉터리에서 다음 순서로 disposable local stack만 사용한다. `ci_dashboard_smoke.py`는 synthetic success/failure를 실제 Collector→Prometheus/Tempo→Grafana query와 대조하고 8초 job/window, 6초 step/module fixture, drilldown frame, duplicate replay를 검사한다. `collector_outage_smoke.py`는 이 Compose 프로젝트의 Tempo를 잠시 멈추고 복원한다. 실제 OnMaruBE run이나 Cloud 전송은 이 명령에 포함되지 않는다.

```bash
bash scripts/verify_toolkit.sh
python3 scripts/build_ci_dashboard.py --check
docker compose -f observability/local/compose.yaml up -d --build --wait
PYTHONPATH=src python3 scripts/ci_dashboard_smoke.py --timeout 45
PYTHONPATH=src python3 scripts/collector_outage_smoke.py --exercise-outage
PYTHONPATH=src python3 scripts/ci_dashboard_smoke.py --timeout 45
```

Stack이 healthy이면 로그인 없이 다음 주소에서 확인한다.

- Grafana CI dashboard: <http://127.0.0.1:3000/d/toolkit-ci-benchmark>
- Prometheus query UI: <http://127.0.0.1:9090>
- Tempo: Grafana Explore에서 `local-tempo` datasource를 선택한다. 진단 API는 <http://127.0.0.1:3200>이며 일반 조회는 Grafana Explore를 사용한다.

Grafana의 workflow/job/module/step duration 패널은 같은 measurement boundary의 값만 비교한다. 개선율은 `(baseline 중앙값 - candidate 중앙값) / baseline 중앙값 × 100`으로 계산하고, 양쪽의 개별 3회 값·범위·실패율·queue 조건을 함께 남긴다. 측정 경계가 다른 serial 470초와 critical-path 411.62초를 전체 CI 개선율로 합치지 않는다.

중단 실험 뒤 Tempo가 복구되지 않으면 같은 Compose 파일의 `start tempo`를 실행하고 smoke를 다시 돌린다. 정리가 필요하면 해당 disposable 프로젝트에 한해 `docker compose -f observability/local/compose.yaml down --volumes --remove-orphans`를 실행한다. tmpfs fixture는 삭제되므로 실제 manifest/replay 저장소로 사용하지 않는다. Collector 뒤 실패 카운터와 queue pressure는 Toolkit의 pre-Collector HTTP 실패를 보여주지 않는다. 이 경계는 `export-result.json`, `diagnostics.md`, replay checkpoint에서 별도로 확인한다. Source 완료부터 ACK까지의 lag가 없으면 0이 아니라 unknown/pending으로 기록한다.

## Cloud 수용 체크리스트

아래는 operator가 실제 Cloud와 GitHub 환경에서 실행한 뒤 각 항목의 상태와 증거를 위 JSON에 기록한다. 환경 secret이 없거나 실제 완료 run이 없으면 `pending`으로 둔다.

1. `ci-observability` GitHub environment에 전송 전용 `OTLP_ENDPOINT`와 `OTLP_HEADERS`를 설정하고 보호 규칙을 검토한다. credential은 metrics/traces write만 허용하는 최소 권한 access policy로 발급한다. runtime `ONMARU_SECRET_OTLP_EXPORTER_TOKEN_*`와 공유하지 않는다. 원본 PR/fork와 `CI / verify`는 secret을 받지 않는지 확인하고 token version ID, 생성·교체·폐기 시각만 기록한다.
2. 실제 완료된 성공 run과 의도된 실패 run의 ID, attempt, source conclusion, manifest digest를 먼저 고정한다. `CI Observability`의 완료 이벤트 또는 제한된 `workflow_dispatch` replay는 이 두 source identity에 대해서만 사용한다. `CI / verify` 결론이 export 성공·실패와 무관하게 동일한지 확인한다. 진단 artifact가 없거나 접근 불가하면 `pass`로 바꾸지 않는다.
3. 각 run에서 `export-result.json`의 상태·ACK/pending batch와 `collection.json`의 관측 시각을 기록한다. Mimir에서 위 metric과 dashboard 성공·실패/quality 패널의 값을 조회하고 Tempo에서 trace ID, result, attempt, digest를 확인한다. Grafana frame과 세 drilldown 링크가 실제 run/manifest로 돌아오는지 검사한다. HTTP 200이나 exporter의 sent counter만으로 왕복을 통과시키지 않는다.
4. 승인된 장애 drill에서 401, 503 또는 timeout을 주입한 결과를 `failed`/`partial`/`pending` 진단과 대조한다. 원본 digest, 관측 시각, destination, `replay.sqlite`를 보존하고 동일 source identity로 재실행한다. ACK batch는 재전송되지 않고 미완료 batch만 재개되는지 확인한다. terminal partial은 무작정 재전송하지 않는다. 오래된 timestamp 거부나 `replay_plan_mismatch`는 gap으로 남긴다.
5. Cloud의 실제 active series, accepted/dropped spans와 bytes, query·ingest limits, timestamp age/out-of-order 제한을 측정한다. 운영 admission은 ≤16 CI jobs, ≤16 modules+`other`, ≤2,000 active series/workflow·environment(1,600 경고), ≤1,041 spans/run, ≤100 runs/day, ≤100 MiB/day serialized trace payload다. 대시보드 top-20은 ingest cardinality 제한이 아니다. 초과 시 local rejection/partial 진단을 남기고 Cloud 전송을 보류한다.
6. 구매한 tenant의 metrics 보존 목표 ≤30일, traces 보존 목표 ≤7일과 실제 설정을 확인한다. Billing의 DPM, month-to-date 사용·비용, owner가 승인한 월 상한 및 50/80/100% 대응 담당자를 기록한다. 2,000 series를 60초 scrape하면 2,000 DPM이지만 이 후처리 OTLP는 실제 전송 패턴으로 실측한다. 요금이나 보존 기간을 dashboard JSON에서 추정해 통과시키지 않는다.

Cloud 검증은 repository 환경 secret, Cloud tenant 권한, 실제 source run/manifest가 모두 갖춰진 뒤 수행한다. 현재 이 저장소의 로컬 fixture 결과는 Cloud `pass` 증거가 아니다.
