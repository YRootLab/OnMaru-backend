# v0.3.38 → v0.3.39 release module benchmark

## 결론

`spring-api-postgres-other` 모듈을 release tag마다 서로 다른 GitHub Actions 실행 3회로
측정했다. v0.3.38 중앙값은 **394.40초**, v0.3.39 중앙값은 **457.93초**다.
Toolkit의 release 정책식 `(candidate - baseline) / baseline`으로 **+16.108%**이며 15%를
초과하므로 정본 판정은 `classification=comparable`, `gate=approval_hold`다. 이 결과는
자동 promotion 승인이 아니며 `benchmark-promotion` 검토 대상이다.

## 원본 실행과 선택 값

| release | commit | run / attempt | manifest artifact | wall clock |
| --- | --- | --- | --- | ---: |
| v0.3.38 | `fa95fee386ab1d96d202d7ae74212fa0082f99d7` | [37216463937/1](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216463937/attempts/1) | [11308683546](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216463937/artifacts/11308683546) | 467.05s |
| v0.3.38 | `fa95fee386ab1d96d202d7ae74212fa0082f99d7` | [37216468625/1](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216468625/attempts/1) | [11309106811](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216468625/artifacts/11309106811) | 341.36s |
| v0.3.38 | `fa95fee386ab1d96d202d7ae74212fa0082f99d7` | [37216473479/1](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216473479/attempts/1) | [11308467756](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216473479/artifacts/11308467756) | 394.40s |
| v0.3.39 | `86a09b313309ff6e89deef56d6b4dfbd83612eb0` | [37216477952/1](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216477952/attempts/1) | [11308967730](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216477952/artifacts/11308967730) | 471.61s |
| v0.3.39 | `86a09b313309ff6e89deef56d6b4dfbd83612eb0` | [37216482685/1](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216482685/attempts/1) | [11309012472](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216482685/artifacts/11309012472) | 411.86s |
| v0.3.39 | `86a09b313309ff6e89deef56d6b4dfbd83612eb0` | [37216487748/1](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216487748/attempts/1) | [11309032724](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216487748/artifacts/11309032724) | 457.93s |

- baseline 범위: 125.69초
- candidate 범위: 59.75초
- 실패·취소·제외: 0
- 유효 표본: baseline 3, candidate 3
- config catalog SHA-256: `880fe045d2549061d799b993bf09eb953f671f925fab77251b8f882aa54dff0d`
- Toolkit pin: `7ecbb89aae771604d9c1c532cf123f239e279110`

검토된 envelope는 [v0.3.38 release asset](https://github.com/YRootLab/OnMaru-backend/releases/download/v0.3.38/release-module-evidence.json)과
[v0.3.39 release asset](https://github.com/YRootLab/OnMaru-backend/releases/download/v0.3.39/release-module-evidence.json)에,
정본 비교 JSON은 [v0.3.39 comparison asset](https://github.com/YRootLab/OnMaru-backend/releases/download/v0.3.39/release-module-comparison.json)에 보존했다.

## 비교 가능성 경계

두 tag에서 같은 workflow, module command, config catalog, repository toolchain과 cold/unrestored
cache 조건을 사용했다. 현 Module Benchmark artifact는 GitHub-hosted runner의 정확한 image build,
Python 버전과 CPU SKU를 수집하지 않는다. 따라서 envelope의 해당 값은
`github-hosted-ubuntu-latest`, `not-captured-by-module-workflow`,
`github-hosted-standard`라는 검토된 catalog label이며 관측하지 않은 세부 사양을 추정하지 않는다.
이 결과는 세 표본의 정책 gate이지 통계적 유의성이나 원인 분석이 아니다.

## 재현

각 tag에서 **정확히 세 번** `Module Benchmark`를 수동 실행하고 완료될 때까지 기다린다.
재실행 attempt나 자동 retry를 섞지 않는다. 각 실행의
`module-evidence-spring-api-postgres-other` artifact에서 `execution.json`을 검토해 release별
`release-module-evidence.json`을 만든 뒤 해당 GitHub Release asset으로 올린다.

```bash
gh workflow run module-benchmark.yml --repo YRootLab/OnMaru-backend --ref v0.3.38
gh workflow run module-benchmark.yml --repo YRootLab/OnMaru-backend --ref v0.3.39

gh release download v0.3.38 --repo YRootLab/OnMaru-backend \
  --pattern release-module-evidence.json --dir /tmp/onmaru-release/baseline
gh release download v0.3.39 --repo YRootLab/OnMaru-backend \
  --pattern release-module-evidence.json --dir /tmp/onmaru-release/candidate

PYTHONPATH=/path/to/OnMaru-backend-ci-toolkit/src \
python3 scripts/benchmark/release-module-comparison.py \
  --baseline /tmp/onmaru-release/baseline/release-module-evidence.json \
  --candidate /tmp/onmaru-release/candidate/release-module-evidence.json \
  --baseline-tag v0.3.38 \
  --baseline-sha fa95fee386ab1d96d202d7ae74212fa0082f99d7 \
  --candidate-tag v0.3.39 \
  --candidate-sha 86a09b313309ff6e89deef56d6b4dfbd83612eb0 \
  --repository YRootLab/OnMaru-backend \
  --output /tmp/onmaru-release/release-module-comparison.json
```

운영 release gate는 같은 adapter를 호출하는 `benchmark-release.yml` 하나만 판정을 소유한다.
기존 W4 latency 비교는 진단 전용이며 release 승인 결정을 만들지 않는다.
