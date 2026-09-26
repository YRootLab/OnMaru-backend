# Serial CI baseline 수집

Issue #368은 fan-out 이전의 serial `verify` 실행을 비교 가능한 baseline으로 보관한다. 이 문서는 raw 로그를 repository에 커밋하지 않는다. GitHub Actions artifact URL과 정규화된 manifest만 PR 또는 Issue에 연결한다.

## 수집 조건

- 동일한 `develop` commit SHA에서 성공한 `verify` run 정확히 세 개를 수집한다.
- 세 run의 runner image, dependency mode, cache state, Java/Python version은 같아야 한다.
- 각 run은 immutable `https://github.com/YRootLab/OnMaru-backend/actions/runs/<run-id>` URL, 실행 command, step duration, CPU time, peak RSS를 제공해야 한다.
- 실패, 취소, artifact 누락, 조건 불일치는 candidate를 폐기한다. 이를 성능 회귀로 해석하지 않는다.

직렬 `CI` workflow에는 `workflow_dispatch`가 있어 같은 `develop` SHA를 세 번 실행할 수 있다. `Collect CI Baseline Evidence` workflow는 기본 브랜치에 등록되어야 하며, 수집 대상 run은 fan-out 전 직렬 topology에서 생성된 성공 run이어야 한다.

## Manifest 생성

세 run의 정규화 입력을 `runs.json`에 준비한 뒤 실행한다.

```bash
node scripts/benchmark/serial-baseline.mjs \
  --input /tmp/serial-runs.json \
  --output /tmp/serial-baseline.json
```

## GitHub Actions API 자동 수집

`Collect CI Baseline Evidence` workflow를 수동 실행하고, 동일한 commit과 실행 identity를 가진 성공한 `CI / verify` run ID 세 개를 쉼표로 전달한다. workflow는 GitHub Actions API에서 run/job/step 시간을 읽어 정규화한 뒤, `serial-baseline.json`과 사람이 읽을 수 있는 `summary.md`를 30일 artifact로 보관한다.

Actions API는 step duration을 제공하지만 CPU와 peak RSS를 제공하지 않는다. 이 경우 수집기는 해당 값을 `0`으로 만들지 않고 `unavailable-from-actions-api`로 표시한다. 자원 수치 비교가 필요하면 이후 CI lane에서 명시적 resource collector artifact를 추가해야 한다.

출력 manifest에는 commit/configuration identity, 세 Actions artifact URL, wall-clock median, work median, peak RSS와 step evidence가 포함된다. `/tmp/serial-baseline.json`을 Actions artifact로 올리고 Issue #368에 세 run URL 및 artifact URL을 기록한다.

## 2026-09-26 확정 기준선

- collector run: [36212018558](https://github.com/YRootLab/OnMaru-backend/actions/runs/36212018558)
- artifact: `ci-serial-baseline-36212018558` (artifact ID `10895259847`, 30일 보관)
- source commit: `34276f201ce6f7b5ffb6e7ab2784eb584a91a699`
- source runs: `36159816646` 413초, `36160594916` 361초, `36161322635` 400초
- wall-clock 중앙값: 400,000ms
- identity: `ubuntu-latest`, dependency mode `locked`, cache state `unknown`, Java 21, Python 3.12
- resource evidence: `unavailable-from-actions-api`

최초 collector 결과는 성공 step 합계를 job wall-clock으로 잘못 사용해 396,000ms를 기록했다. collector가 `verify` job의 `started_at`과 `completed_at`을 보존하도록 수정하고 다시 수집했으며, 위 artifact는 413초·361초·400초의 실제 job duration과 400초 중앙값을 포함한다. summary의 run URL도 실제 줄바꿈으로 렌더링된다.

## 종료 기준

Issue #368은 다음이 모두 충족될 때만 종료한다.

1. `serial-baseline.test.mjs`가 valid·failed·incomparable 입력을 검증한다.
2. 동일한 `develop` SHA의 valid serial run 세 개가 artifact URL과 함께 기록된다.
3. 생성한 manifest가 #364 fan-out PR의 S1 비교 입력으로 연결된다.
