# Serial CI baseline 수집

Issue #368은 fan-out 이전의 serial `verify` 실행을 비교 가능한 baseline으로 보관한다. 이 문서는 raw 로그를 repository에 커밋하지 않는다. GitHub Actions artifact URL과 정규화된 manifest만 PR 또는 Issue에 연결한다.

## 수집 조건

- 동일한 `develop` commit SHA에서 성공한 `verify` run 정확히 세 개를 수집한다.
- 세 run의 runner image, dependency mode, cache state, Java/Python version은 같아야 한다.
- 각 run은 immutable `https://github.com/YRootLab/OnMaru-backend/actions/runs/<run-id>` URL, 실행 command, step duration, CPU time, peak RSS를 제공해야 한다.
- 실패, 취소, artifact 누락, 조건 불일치는 candidate를 폐기한다. 이를 성능 회귀로 해석하지 않는다.

현재 CI workflow에는 `workflow_dispatch`가 없으므로, 세 run은 자연 발생 `develop` run에서 수집한다. fan-out 전 baseline을 조작하기 위해 직접 push하거나 workflow topology를 변경하지 않는다.

## Manifest 생성

세 run의 정규화 입력을 `runs.json`에 준비한 뒤 실행한다.

```bash
node scripts/benchmark/serial-baseline.mjs \
  --input /tmp/serial-runs.json \
  --output /tmp/serial-baseline.json
```

출력 manifest에는 commit/configuration identity, 세 Actions artifact URL, wall-clock median, work median, peak RSS와 step evidence가 포함된다. `/tmp/serial-baseline.json`을 Actions artifact로 올리고 Issue #368에 세 run URL 및 artifact URL을 기록한다.

## 종료 기준

Issue #368은 다음이 모두 충족될 때만 종료한다.

1. `serial-baseline.test.mjs`가 valid·failed·incomparable 입력을 검증한다.
2. 동일한 `develop` SHA의 valid serial run 세 개가 artifact URL과 함께 기록된다.
3. 생성한 manifest가 #364 fan-out PR의 S1 비교 입력으로 연결된다.
