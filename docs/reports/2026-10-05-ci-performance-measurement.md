# CI Gradle profile 3+3 실측

Issue #525와 #556의 수동 `ci` scope 실험을 2026-10-05에 실행했다. Controller는 [run 37215998513](https://github.com/YRootLab/OnMaru-backend/actions/runs/37215998513)이며, 고정 Toolkit `7ecbb89aae771604d9c1c532cf123f239e279110`가 여섯 run의 attempt 1, artifact identity, 공통 source tree와 test plan을 검증했다. 최종 manifest의 build provenance attestation도 통과했다.

## 비교 조건

| 항목 | Baseline | Candidate |
| --- | --- | --- |
| Config ref | `a91698fc01257e06111cdc51de1745581c0d337d` | `b7f1d0df1a34037db4dfa0fda75fd088dde1b5ab` |
| Application source | `a91698fc01257e06111cdc51de1745581c0d337d` | baseline과 동일 |
| Gradle workers | 4 | 2 |
| Gradle build cache | enabled | disabled |
| Suite | `full-java-build` | `full-java-build` |
| Runner | `ubuntu24/20260927.320.1`, 4 CPU / 16 GiB | 동일 |
| Java / Python | `javac 21.0.12.1` / `Python 3.12.14` | 동일 |
| Cache state | cold | cold |
| Test plan digest | `a8061ce232bae7e6f284aa6782f619a5808b003d53f261f396fcce932e54e883` | 동일 |

Candidate branch의 다른 소스는 실행하지 않았다. Controller가 baseline tree를 공통 application source로 checkout하고 allowlist된 두 Gradle 값만 candidate 입력으로 적용했다.

## 결과

| Side | 1회 | 2회 | 3회 | 중앙값 | 범위 | 실패율 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Baseline | [495초](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216011695) | [711초](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216015843) | [712초](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216019887) | 711초 | 217초 | 0% |
| Candidate | [751초](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216013912) | [545초](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216017960) | [541초](https://github.com/YRootLab/OnMaru-backend/actions/runs/37216021951) | 545초 | 210초 | 0% |

Toolkit 정본 결과는 `classification=comparable`, `verdict=no_regression`, absolute delta `-166초`, relative delta `-0.23347398030942335`다. Toolkit의 relative delta는 `(candidate - baseline) / baseline`이므로 음수는 단축을 뜻한다. 사람이 읽는 개선율로 바꾸면 candidate 중앙값이 baseline보다 약 **23.35% 짧다**.

여섯 artifact와 최종 manifest는 [controller artifact 11308541982](https://github.com/YRootLab/OnMaru-backend/actions/runs/37215998513/artifacts/11308541982)에 연결된다. Exclusion은 없고 모든 run과 artifact identity가 `verified`다.

## 결정과 한계

현재 shared Java CI profile을 candidate의 workers 2, build cache disabled로 바꾼다. required `verify`의 테스트 범위와 명령, application source, runner class는 바꾸지 않는다.

다만 각 side 범위가 210초 이상이고 표본이 3회뿐이므로 23.35%를 통계적으로 확정된 장기 개선으로 해석하지 않는다. GitHub-hosted runner와 dependency download 변동이 포함됐다. 변경 후 실제 `CI / verify` 추세가 악화되면 동일 절차로 재측정하고 baseline 값으로 되돌린다.

## 재현

Receipt와 raw collection은 저장소 밖 `/tmp`에 두고, 저장소의 Skill과 고정 Toolkit으로 `dry-run → dispatch → wait → compare`를 실행한다. 구체 명령은 [README](../../README.md#benchmark-skill-실행)와 [benchmark 운영 문서](../benchmark/README.md#수동-pipeline-benchmark-experiment-556)에 있다. Dispatch 응답이 모호하거나 실패하면 자동 재실행하지 않는다.
