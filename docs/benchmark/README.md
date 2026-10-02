# Release benchmark evidence

이 디렉터리는 release tag부터 staging 검증, release metadata, image digest, readiness,
baseline 비교, evidence manifest와 promotion gate까지의 W4 검증 계약을 설명한다.

`Module Benchmark`는 CI·테스트 코드·테스트 도구 변경이 `develop`에 반영될 때만 자동으로
증적을 수집하며, 필요하면 `workflow_dispatch`로 수동 실행한다. 일반 코드 변경 PR에서는
필수 `CI / verify`만 실행한다. 자동 실행 경로 목록은
`.github/workflows/module-benchmark.yml`의 `push.paths`가 기준이다.

`CI Observability`는 완료된 `CI`와 `Module Benchmark` 실행을 별도 workflow에서 후처리한다.
실패·취소된 원본 실행도 원래 결론을 보존하며, 후처리의 실패가 필수 `CI / verify` 판정을
바꾸지 않는다. 수동 재처리는 `workflow_dispatch`에 원본 run ID, attempt, workflow 이름을
지정한다. 수집기는 해당 attempt의 Actions API job 페이지와 (모듈 실행에 한해) 제한된
`execution.json`을 검증하고, 고정된 Toolkit commit
`59b3344ecdd4460451e4e67db973d6dfd4afa8b5`로 evidence를 정규화한다. artifact의
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
node --test scripts/test/benchmark-release-pipeline.test.mjs
node --test scripts/test/benchmark-compare.test.mjs scripts/test/workflow-benchmark-release.test.mjs
node --test scripts/test/ci-observability-workflow.test.mjs scripts/test/module-benchmark-caller.test.mjs
```

기존 release 테스트는 fake toolkit과 loopback readiness fixture를 사용한다. CI 관측
테스트는 로컬에 고정 Toolkit 소스가 있으면 이를 사용하고, 일반 CI hygiene에서는 저장소의
오프라인 API fixture를 사용한다. 실제 staging 배포나 Cloud export를 수행하지 않는다.
