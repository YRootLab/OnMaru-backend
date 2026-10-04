# CI 관측·벤치마크 실 통합 검증 설계

## 목적

Issues #543, #554, #555, #556에서 이미 병합된 CI 관측 및 3회 비교 계약을 실제 GitHub Actions와 Grafana Cloud에서 검증한다. 합성 fixture 통과를 실환경 완료로 간주하지 않으며, Actions run·artifact·Grafana query가 서로 연결되는 증거를 남긴 뒤 완료 조건을 충족한 이슈만 닫는다.

## 선택한 접근

실제 Cloud 전송과 실제 Actions 실행을 사용하되 release 이미지 배포와 성능 판정을 분리한다.

1. `CI Observability`는 `ci-observability` environment의 전송 전용 OTLP credential로 기존 성공·실패 run을 재처리한다.
2. `Pipeline Benchmark Experiment`는 사용자가 이번 대화에서 승인한 실제 dispatch로 baseline/candidate 각 3회를 실행한다.
3. release 비교는 release tag SHA에서 실행한 서로 다른 `Module Benchmark` 3회씩을 선택해 canonical `release-module-evidence.json`을 만든다. 비교 검증만을 위해 production image를 다시 build/push하거나 staging을 배포하지 않는다.
4. `benchmark-release.yml`의 promotion 경로는 계속 build·staging 전제 조건을 유지한다. 실측 증거 검증 경로가 production promotion을 우회하지 않도록 별도 입력과 명확한 job 조건을 둔다.

검토했지만 채택하지 않은 대안은 로컬·fixture만으로 이슈를 닫는 방법과, 과거 tag 검증을 위해 전체 release image build·staging 배포를 재실행하는 방법이다. 전자는 실환경 완료 조건을 충족하지 못하고 후자는 benchmark 검증 범위를 넘어 registry·staging 상태를 변경한다.

## 구성 요소와 데이터 흐름

### CI Observability (#554, #555)

- 원본 `CI`와 `Module Benchmark` run ID/attempt를 명시해 수동 replay한다.
- collect job은 GitHub API와 제한된 artifact만 읽고 diagnostic artifact를 만든다.
- export job만 Grafana credential을 받으며 OTLP HTTP endpoint로 metrics와 traces를 보낸다.
- 성공·의도된 실패·credential 제거 또는 잘못된 endpoint를 이용한 outage drill을 각각 실행한다.
- Grafana에서 run ID로 metric과 trace를 조회하고 Actions run 및 manifest URL로 역추적한다.
- token rotation은 새 token 등록 → 성공 replay → 기존 token 폐기의 순서로 검증하며 원문 token은 문서나 artifact에 남기지 않는다.

### Pipeline Benchmark Experiment (#556, Toolkit #122)

- `develop`의 immutable SHA를 baseline으로 고정한다.
- candidate는 동일 application tree에서 허용된 Gradle 설정만 바뀐 `feature/*` SHA를 사용한다.
- controller가 baseline/candidate 각각 ordinal 1–3을 dispatch하고 attestor가 여섯 artifact, API identity, ref immutability를 재검증한다.
- 최종 manifest와 Toolkit compare 결과에는 개별 값, 중앙값, 범위, delta, 제외 사유와 Actions 링크를 남긴다.
- 일반 PR/push에는 benchmark job을 추가하지 않는다.

### Release 3회 비교 (#543)

- baseline/candidate release tag와 SHA를 먼저 고정한다.
- 각 tag에서 `Module Benchmark`를 서로 다른 run ID로 3회 실행한다.
- collector는 API의 run/attempt/SHA/conclusion, artifact digest와 bounded execution evidence를 검증해 release별 envelope를 만든다.
- envelope에는 정확히 3개의 유효하고 비교 가능한 표본만 포함하며 중복·취소·실패·환경 불일치는 제외 사유로 남긴다.
- canonical comparator가 중앙값과 15% 경계를 판정한다. `approval_hold`일 때만 `benchmark-promotion` environment 승인이 필요하고, `failed`/`inconclusive`는 승인으로 우회할 수 없다.

## 오류 처리와 신뢰 경계

- OTLP credential이 없으면 export를 실행하지 않고 명시적인 `not_configured` diagnostic으로 끝낸다. credential이 있는데 전송이 실패하면 export failure를 관측하되 원본 required CI verdict는 바꾸지 않는다.
- fork/PR 코드는 credential job에서 실행하지 않는다.
- Actions artifact는 크기·schema·run ID·attempt·SHA·digest·URL allowlist를 모두 검증한다.
- benchmark controller는 움직인 ref, 같은 baseline/candidate SHA, dirty branch, 중복 run, rerun attempt를 fail-closed 처리한다.
- release verification-only 실행은 image push, staging secret, 배포 및 GitHub Release asset 덮어쓰기를 금지한다. 최종 채택 증거의 release asset 게시가 필요하면 검증 성공 뒤 별도 명시 단계에서만 수행한다.

## 문서 계약

루트 `README.md`에는 다음 사용 경로를 제공한다.

- 로컬 Grafana stack 기동·종료와 synthetic smoke
- GitHub `ci-observability` environment의 endpoint/header 생성 및 90일 이내 rotation
- 기존 Actions run 수동 replay와 diagnostic artifact 확인
- Skill dry-run, 실제 dispatch, wait, collection 추출, Markdown/JSON compare
- release tag별 3회 수집, 비교-only 검증, `approval_hold` 승인 절차
- 실패 시 확인할 run/artifact/query와 secret을 출력하지 않는 진단 명령

상세 보안·운영 설명은 `docs/benchmark/README.md`와 `docs/operations/release-evidence/*.md`에 유지하고 루트 README에서는 실행 순서와 링크를 제공한다.

## 검증과 종료 조건

- #554: success/failure/cancelled 또는 동등한 fixture+live run 처리, secret-free replay, 중복 억제, required CI 독립성이 Actions 증거로 확인돼야 한다.
- #555: Grafana Cloud metric·trace 조회, outage/recovery, credential rotation, retention/cardinality/cost 기록이 있어야 한다.
- #556 및 Toolkit #122: 실제 3+3 dispatch 1회와 attestation/compare가 성공해야 한다.
- #543: release tag별 유효 run 3개씩, 중앙값 비교, 15% 경계와 approval environment 동작이 확인돼야 한다.
- 각 이슈는 관련 PR이 `develop`에 병합되고 fresh verification과 live evidence 링크가 기록된 뒤 닫는다. 일부 조건만 충족한 이슈는 열린 상태로 둔다.

