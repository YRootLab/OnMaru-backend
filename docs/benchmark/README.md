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
`08501bf55a373e27782c89cca348040fa1affa93`로 evidence를 정규화한다. artifact의
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

self-hosted runner 풀은 선택하지 않았다. 채택하려면 상시 인스턴스·스토리지·패치·운영
인력 비용, 작업별 정리와 격리, 외부 PR에서 secret에 접근하지 못하는 신뢰 경계, cache 오염
방지와 장애 대응을 별도로 검증해야 한다. 재검토 조건은 동일 SHA·runner 사양·test plan·
cache/fixture 조건에서 baseline/candidate 각 3회 성공 실행의 같은 경계 workflow wall clock,
실패율·queue·runner 사용량을 수집하는 것이다. 현재 자료에 없는 비용과 p95는 추정하지 않는다.
