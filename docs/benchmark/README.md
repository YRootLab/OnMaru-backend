# Release benchmark evidence

이 디렉터리는 release tag부터 staging 검증, release metadata, image digest, readiness,
baseline 비교, evidence manifest와 promotion gate까지의 검증 계약을 설명한다.

Release 판정은 `benchmark-release.yml`의 `release-module-comparison.json` 한 개가 소유한다.
Toolkit PR #130의 module comparator로 baseline/candidate 각 3회 중앙값을 비교하며, 15% 초과만
승인 검토로 연결한다. 실패·취소·누락은 자동 promotion을 차단한다. 입력은 각 GitHub
Release의 검토된 `release-module-evidence.json` asset이다. W4의 5% 비교와 trend는
진단 전용이며 [정본 계약](contracts.md)에 입력 schema와 신뢰 경계를 명시한다.

`Module Benchmark`는 CI·테스트 코드·테스트 도구 변경이 `develop`에 반영될 때만 자동으로
증적을 수집하며, 필요하면 `workflow_dispatch`로 수동 실행한다. 일반 코드 변경 PR에서는
필수 `CI / verify`만 실행한다. 자동 실행 경로 목록은
`.github/workflows/module-benchmark.yml`의 `push.paths`가 기준이다.

`CI Observability`는 완료된 `CI`와 `Module Benchmark` 실행을 별도 workflow에서 후처리한다.
실패·취소된 원본 실행도 원래 결론을 보존하며, 후처리의 실패가 필수 `CI / verify` 판정을
바꾸지 않는다. 수동 재처리는 `workflow_dispatch`에 원본 run ID, attempt, workflow 이름을
지정한다. 수집기는 해당 attempt의 Actions API job 페이지와 (모듈 실행에 한해) 제한된
`execution.json`을 검증하고, 고정된 Toolkit commit
`7ecbb89aae771604d9c1c532cf123f239e279110`로 evidence를 정규화한다. artifact의
명령·로그는 실행하거나 telemetry에 넣지 않는다.

후처리 산출물 `ci-observability-diagnostic-<run>-<attempt>`에는 `evidence.json`,
`collection.json`, `diagnostics.md`, `export-result.json`, `replay-state.json`,
`replay.sqlite`가 포함된다. `ci-observability` GitHub environment의 `OTLP_ENDPOINT`와
`OTLP_HEADERS`는 export job에서만 사용한다. export 실패 시에도 source conclusion은
그대로이며 결과가 diagnostic artifact에 남는다. 재처리는 14일간 보관되는 이전 diagnostic
artifact의 SQLite checkpoint와 관측 시각을 복원해 이미 확인된 batch를 억제한다. artifact가
만료됐거나 수신 확인이 유실된 경우에는 exactly-once 전송을 보장하지 않는다. 장기 감사가
필요하면 별도의 내구 저장소와 보존 기간을 결정해야 한다.

- [contracts.md](contracts.md): release metadata, comparison, manifest와 toolkit 경계
- [current-state.md](current-state.md): 현재 provenance와 운영 리스크
- [운영 증적 문서](../operations/release-evidence/benchmark.md): DAG, artifact link, Job Summary,
  redaction, known limitations 및 재현 명령

## 검증 진입점

```bash
node --test scripts/test/benchmark-contract.test.mjs
node --test scripts/test/release-module-comparison.test.mjs scripts/test/workflow-benchmark-release.test.mjs
node --test scripts/test/benchmark-release-pipeline.test.mjs
node --test scripts/test/benchmark-compare.test.mjs scripts/test/workflow-benchmark-release.test.mjs
node --test scripts/test/ci-observability-workflow.test.mjs scripts/test/module-benchmark-caller.test.mjs
```

기존 release 테스트는 fake toolkit과 loopback readiness fixture를 사용한다. CI 관측
테스트는 로컬에 고정 Toolkit 소스가 있으면 이를 사용하고, 일반 CI hygiene에서는 저장소의
오프라인 API fixture를 사용한다. 실제 staging 배포나 Cloud export를 수행하지 않는다.

## 수동 Pipeline Benchmark Experiment (#556)

`pipeline-benchmark-experiment.yml`은 `workflow_dispatch`로만 실행하며 `baseline_ref`,
`candidate_ref`, `scope`, `reason`을 받는다. 검토된 `develop` workflow SHA가 baseline과
같을 때만 controller와 attestor를 실행한다. 실험 단위 concurrency는 기존 실험을 취소하지
않으며 일반 PR·push·CD, 필수 `verify` check와 연결하지 않는다.

Controller는 baseline/candidate workflow/config SHA를 한 번 선택한 뒤 각각 ordinal 1–3을
별도 `pipeline-benchmark-sample.yml` run으로 dispatch한다. API에는 문서상 지원되는 branch
이름을 보내고, 매 POST 직전·직후 두 branch SHA와 수집한 run의 실제 `head_sha`를 고정 SHA에
대조한다. Candidate SHA를 가리키는 `feature/*` branch가 정확히 하나여야 한다. Ref가 이동하면
`refs_moved`로 중단한다. API 응답의 정확한 run ID만 사용하며 응답 유실·실패·취소·누락 때
자동 재dispatch 또는 최신 run 검색은 하지 않는다. 이미 발행한 receipt는 즉시 보존한다.

두 ref의 sample workflow 원본 바이트가 같아야 한다. 실행 application source는 baseline의
전체 Git tree로 통일하며 `.github/pipeline-benchmark-test-plan.json`의 원본 바이트 SHA-256을
기록한다. `version: 1, scopes: {ci: {...}, test: {...}}` plan에서 `ci`는 9개 Java test task와
`bootJar`, `test`는 같은 9개 test task를 실행한다. 두 scope 모두 test filter를 축소하지 않는다.
이 suite는 Java lane 실험이며 repository 전체 CI의 Python·contract lane을 측정했다고
표현하지 않는다. 모듈·명령 argv·fixture·seed·dependency mode는 committed plan에 있다.

Candidate에서 사용하는 설정은 `gradle.properties`의
`onmaru.ci.performance.max-workers`(1–4)와 `onmaru.ci.performance.build-cache`(boolean)
두 개뿐이다. 다른 candidate 코드·명령·workflow 변경은 실행 입력이 되지 않는다. Worker는
공통 source를 checkout한 뒤 실제 HEAD/tree, committed plan과 dirty/untracked 상태를 실행
전후에 확인한다. Shell 없이 committed argv를 실행하며 subprocess에는 GitHub token,
Actions token, OTLP 관련 환경변수를 전달하지 않는다. 새로운 설정 종류나 runner topology를
비교하려면 별도 검토를 통해 trusted worker와 config catalog를 확장해야 한다.

현재 cache 조건은 GitHub-hosted `ubuntu-24.04` 새 runner의 cold cache다. Cache restore는
사용하지 않으며 Java/Python 버전, runner image version, CPU/메모리, fixture, dependency
mode 및 config catalog hash를 증적에 남긴다. 이 8개 환경 필드가 여섯 표본에서 다르면
`inconclusive`다. 여섯 run은 dispatch 후 독립적으로 실행되므로 queue·공유 runner 잡음은
남으며 3회 표본으로 통계적 유의성을 주장하지 않는다.

Worker에는 `contents: read`만 있고 signing·OTLP secret은 없다. 별도 attestor는 candidate
코드를 실행하지 않고 API의 run/attempt/SHA/run-name/artifact 신원, baseline Git tree와 plan
원본, trusted worker의 실제 checkout 증적을 다시 검증한다. 최종 `experiment-manifest.json`
원본 바이트에 provenance를 발행한 뒤 동일 파일 하나를
`pipeline-experiment-manifest-<attempt>` artifact로 올린다. Sample artifact는 각 run의
`pipeline-experiment-sample-<attempt>`이며 run-name은 정확히
`pipeline-experiment/<experiment_run_id>/<side>/<ordinal>`이다. 현재 producer는 rerun을
혼합하지 않도록 experiment/sample attempt 1만 지원한다.

Whole-workflow 시간은 GitHub run usage API의 `run_duration_ms`만 초로 변환한다. 해당 API는
[종료 예정으로 공지되어 있으므로](https://docs.github.com/en/rest/actions/workflow-runs#get-workflow-run-usage)
필드 누락·API 종료·시간선 불확실성은 `wall_clock_unavailable`과 `inconclusive`로 남긴다.
`updated_at`, job window, 최장 module 시간으로 대체하지 않는다. 표본 6개를 검증하지 못하면
성공 manifest와 attestation을 만들지 않으며 `pipeline-experiment-control-<attempt>`의
`receipts.json`, `diagnostic-control.json` 및 attestor의
`pipeline-experiment-diagnostic-<attempt>`를 확인한다. 원본 run이 강제 취소되어 artifact
업로드 단계도 실행되지 않으면 GitHub run 자체와 이미 업로드한 receipt가 복구 기준이다.

실행 진입점은 Toolkit의 dry-run → 명시적 dispatch → wait → compare다. Receipt/result는
working tree 밖에 저장한다. 실제 비교는 Toolkit의 `pipeline-experiment/2` 검증과 comparator가
소유하며 3회 중앙값의 15% 초과 회귀는 `approval_review`, 결측은 `inconclusive`다. 이 producer는
성능 verdict를 재구현하지 않는다. Grafana URL은 현재 생성하지 않으며 관측 없이도 증적을
보존한다.

```bash
pipeline-toolkit experiment dry-run --repo-root "$PWD" --scope ci --reason 'Gradle worker 비교'
# 최초 통합 1회만 #135 bootstrap gate를 명시한다. 이후에는 두 이슈가 닫혀 있어야 한다.
pipeline-toolkit experiment dispatch --repo-root "$PWD" --scope ci --reason 'Gradle worker 비교' --bootstrap-integration > /tmp/onmaru-experiment-receipt.json
pipeline-toolkit experiment wait --receipt /tmp/onmaru-experiment-receipt.json > /tmp/onmaru-experiment-result.json
# result의 collection 객체를 별도 collection.json으로 저장한 뒤 offline 재계산한다.
pipeline-toolkit experiment compare --input /tmp/onmaru-experiment-collection.json --format markdown
node --test scripts/test/pipeline-benchmark-experiment.test.mjs
```

Toolkit #135/PR #136은 최초 통합에 한해 `--bootstrap-integration`을 허용한다. 이 모드는 #555가
닫혔고 #556만 열려 있는 상태, 정확한 저장소·workflow·scope에서만 동작하며 receipt에 bootstrap
사용 사실을 남긴다. 일반 실행은 OnMaruBE #555/#556이 모두 닫혀 있어야 하며 helper나 skill로
gate를 우회하지 않는다. 또한 두 workflow 모두
[default branch에 존재해야 수동 실행이 가능하므로](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch)
`develop` 병합만으로 live availability를 주장하지 않는다. Multi-scope plan 검증은
[Toolkit #131](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/issues/131) /
[PR #132](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/pull/132)의 merge commit
`7ecbb89aae771604d9c1c532cf123f239e279110`로 고정한다. 기존 단일-scope Toolkit 버전은 이
plan을 수집할 수 없으므로 동일 commit으로 설치해야 한다. Module/release/관측 후처리의
활성 pin도 같은 commit으로 맞췄다. Fake API/로컬 테스트 통과는 실제 dispatch, 기본 브랜치 가용성,
시간 API 가용성, provenance 업로드 검증을 대신하지 않는다.

### 2026-10-05 실제 `ci` 3+3 결과

[controller run 37215998513](https://github.com/YRootLab/OnMaru-backend/actions/runs/37215998513)에서 baseline `4 workers/cache`와 candidate `2 workers/no-cache`를 같은 application source와 committed plan으로 실행했다. Baseline `[495, 711, 712]`초의 중앙값은 711초, candidate `[751, 545, 541]`초의 중앙값은 545초였다. 여섯 run 모두 성공했고 artifact identity와 최종 attestation이 검증됐다. Toolkit verdict는 `no_regression`, relative delta는 `-0.23347398030942335`다. 이 필드는 `(candidate - baseline) / baseline`이므로 약 23.35% 단축을 뜻한다. 각 side 범위가 217초와 210초라 3회 결과만으로 통계적 유의성을 주장하지 않는다. 전체 provenance와 개별 링크는 [실측 보고서](../reports/2026-10-05-ci-performance-measurement.md)에 보존한다.

## Java required lane 유지 결정 (#525)

현재 required CI는 shared GitHub-hosted runner의 `Java 모듈 및 Spring API 전체 테스트`
lane을 유지한다. Tourism adapter, 7개 도메인 모듈, Spring API 전체 테스트 및 `bootJar`가
실행되며 `verify`는 이 lane의 성공을 계속 요구한다. 이번 결정으로 테스트 범위를 줄이거나
module matrix로 전환하지 않는다.

[기존 shard 실험](../reports/2026-09-29-issue-465-spring-api-shard-benchmark.md)의 candidate
전체 workflow 중앙값은 **725초**, serial 기준선은 **470초**였다. shard 최대시간의 중앙값
**411.62초**는 critical-path proxy라 전체 workflow와 측정 경계가 같지 않다. 이 값을
성능 개선 근거로 사용하지 않으며 이번 작업에서 새 실측이나 성능 향상을 주장하지 않는다.

shared-runner Gradle 설정은 `-Ponmaru.ci.performance.enabled=true`로 활성화되는
`max-workers=4`와 build cache를 opt-in으로 유지한다. worker 상한은
`org.gradle.parallel=true`와 같지 않다. module별 runner matrix는 job마다 runner 생성·종료,
Java 설치·dependency 준비·artifact 병합을 반복하므로 전체 wall clock과 runner 사용량을
함께 측정해야 한다.

공통 Java CI lane과 두 수동 benchmark scope는 `--init-script build-logic/ci-performance.gradle.kts`를
명시하여 위 property를 실제 Gradle 실행 설정에 적용한다. Property 전달만으로는 이 script가
로드되지 않는다. `gradle-ci-performance.test.mjs`는 실제 wrapper로 격리된 임시 project의
`ciPerformanceProfile`을 실행해 effective worker 수와 cache 설정을 확인한다. 전체 Node 테스트를
실행할 때도 JDK와 Gradle wrapper distribution이 필요하며 이 회귀 테스트는 `--offline`으로
application dependency를 resolve하지 않는다.

self-hosted runner 풀은 선택하지 않았다. 채택하려면 상시 인스턴스·스토리지·패치·운영
인력 비용, 작업별 정리와 격리, 외부 PR에서 secret에 접근하지 못하는 신뢰 경계, cache 오염
방지와 장애 대응을 별도로 검증해야 한다. 재검토 조건은 동일 SHA·runner 사양·test plan·
cache/fixture 조건에서 baseline/candidate 각 3회 성공 실행의 같은 경계 workflow wall clock,
실패율·queue·runner 사용량을 수집하는 것이다. 현재 자료에 없는 비용과 p95는 추정하지 않는다.
