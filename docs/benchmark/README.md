# Release benchmark evidence

이 디렉터리는 release tag부터 staging 검증, release metadata, image digest, readiness,
baseline 비교, evidence manifest와 promotion gate까지의 W4 검증 계약을 설명한다.

`Module Benchmark`는 CI·테스트 코드·테스트 도구 변경이 `develop`에 반영될 때만 자동으로
증적을 수집하며, 필요하면 `workflow_dispatch`로 수동 실행한다. 일반 코드 변경 PR에서는
필수 `CI / verify`만 실행한다. 자동 실행 경로 목록은
`.github/workflows/module-benchmark.yml`의 `push.paths`가 기준이다.

- [contracts.md](contracts.md): release metadata, comparison, manifest와 toolkit 경계
- [current-state.md](current-state.md): 현재 provenance와 운영 리스크
- [운영 증적 문서](../operations/release-evidence/benchmark.md): DAG, artifact link, Job Summary,
  redaction, known limitations 및 재현 명령

## 검증 진입점

```bash
node --test scripts/test/benchmark-contract.test.mjs
node --test scripts/test/benchmark-release-pipeline.test.mjs
node --test scripts/test/benchmark-compare.test.mjs scripts/test/workflow-benchmark-release.test.mjs
```

테스트는 fake toolkit과 loopback readiness fixture만 사용한다. 실제 staging 배포나
pipeline-toolkit 설치를 수행하지 않으며, production adapter/runtime/workflow 구현의
동작을 대체하지 않고 계약 회귀를 검증한다.
