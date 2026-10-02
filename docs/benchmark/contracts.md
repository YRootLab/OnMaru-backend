# Benchmark evidence contracts

## 계약 상태

Release promotion의 정본은 `release-module-comparison.json`이다. Toolkit PR #130 병합 commit
`d5b7892875000afc2deba6e6873717974d558ee5`의 Python API
`compare_module_benchmarks(target=EvaluationTarget.RELEASE)`가 수치 정책을 소유한다.
현재 pin에는 이 module 비교를 노출하는 CLI가 없으므로
`scripts/benchmark/release-module-comparison.py`가 고정 checkout의 API를 직접 호출한다.
중앙값·범위·15% 정책을 OnMaruBE에서 다시 계산하지 않는다.

[Toolkit #129](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/issues/129)의
[PR #130](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/pull/130) 수정으로 `1.4 → 1.61`은
정확히 15%이며 승인 대상이 아니다. 이를 조금이라도 초과하면 `approval_hold`다. decimal
경계 판정과 bool/NaN/±Infinity 거부는 upstream 계약을 사용하며 adapter에 epsilon을 추가하지
않는다.

기존 W4 `release metadata`와 evidence 필드는 진단용 v1 계약으로 유지한다.
`.pipeline/benchmark.yml`의 가상 CLI provenance는 계속 `unverified`이며 W4 5% threshold,
`gate.json`, trend 결과는 promotion 판정을 소유하지 않는다.

## Release module 입력과 정본 결과 (#543)

운영자가 baseline/candidate GitHub Release 각각에 `release-module-evidence.json`을 제공한다.
workflow는 baseline lookup으로 선택한 tag와 실제 checkout된 candidate tag의 SHA를 사용한다.
각 envelope는 다음 모양이며 `records`의 전체 필드 예시는
`scripts/test/fixtures/release-module-comparison/record.json`에 있다.

```json
{
  "schema_version": 1,
  "release_tag": "v1.2.4",
  "commit_sha": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
  "records": []
}
```

각 side는 동일 모듈의 서로 다른 Actions run 3개를 사전에 선택한다. 2개 이하는
`inconclusive`이며 4개 이상을 주어 유리한 3개를 사후 선택하는 것도 허용하지 않는다.
run attempt만 바꾼 재실행은 서로 다른 run으로 세지 않고 양쪽의 동일 run 재사용도 막는다.
원본 `Module Benchmark`의 develop 진단 aggregate는 자체적으로 release 판정이 아니며,
이 envelope와도 schema가 다르다. 현재 workflow가 새 benchmark run을 자동 생성하거나
개별 manifest를 envelope로 자동 변환하지는 않는다.

레코드는 `module_id`, `run_id`, `run_attempt`, `actions_url`, `manifest_url`, `metric_name`,
`metric_value`, `unit`, `resource`, `provenance`, `artifact_uri`, `environment_identity`,
`status`, `complete`만 소비한다. 측정 경계는 동일 모듈의 `module.wall_clock` seconds다.
이는 Java required CI 전체 workflow wall clock을 뜻하지 않으며, manifest의 null
`workflow_wall_clock_seconds`를 최장 모듈 시간이나 0으로 치환하지 않는다.

provenance는 해당 repository·release commit SHA·`Module Benchmark` workflow와 일치해야
한다. Toolkit comparability key가 모듈·metric·unit·repository/workflow/job·runner image·
Java/Python version·cache·DB fixture·CPU/memory·dependency mode·catalog hash를 비교한다.
Actions URL은 `https://github.com/<repo>/actions/runs/<run>/attempts/<attempt>`, manifest
artifact link는 `https://github.com/<repo>/actions/runs/<run>/artifacts/<artifact>`만 허용한다.
`artifact_uri`는 manifest link와 같아야 한다. credential/query/fragment URL은 배제한다.
이 경계는 release asset 작성 권한을 신뢰하며 원본 Actions API나 artifact bytes를 다시
조회하여 진위를 확인하지 않는다. 운영자는 선택한 run conclusion·commit·manifest와
측정값을 검토한 뒤 asset을 게시해야 한다.

정본은 Toolkit의 `classification`, `reason`, `sample_values`, `sample_range`,
`baseline_median`, `candidate_median`, `relative_delta`, `valid_sample_count`,
`policy_threshold`, `required_samples`, `policy_outcome`을 보존한다. `sources`에 원본
Actions/manifest 링크와 개별 값·상태를 남기고 `exclusions`에는 side/index/reason만 기록한다.
실패·취소·누락·음수/비유한 수·release identity 불일치는 유효 표본에서 제외한다.
중앙값 0으로 delta가 정의되지 않는 v0.1.3 예외는 `inconclusive`로 닫는다.

| 정본 결과 | `gate` | Promotion |
| --- | --- | --- |
| `comparable`, `policy_outcome=none` | `pass` | staging/migration/build 성공 시 통과 |
| `comparable`, `policy_outcome=approval_hold` | `approval_hold` | 15% **초과** 회귀만 `benchmark-promotion` 승인 |
| `failed` 또는 `inconclusive` | `blocked` | 자동 promotion 중지, 회귀 승인 생성 안 함 |

정확히 15%는 승인 대상이 아니다. 승인 job 성공도 scan·migration·readiness 실패를 우회할
수 없다. 입력 artifact가 없어도 정본 실패 사유를 게시하며, parser/import 자체 오류로
비교 job이 실패하면 promotion은 닫힌다.

## Release metadata

`release.json`은 `artifacts/release/<version>/release.json`에 저장한다.

```json
{
  "schemaVersion": 1,
  "releaseId": "v0.4.0",
  "tag": "v0.4.0",
  "commitSha": "40-char-git-sha",
  "environment": "staging",
  "workflowRunId": "123456",
  "services": [
    {
      "name": "spring-api",
      "image": "ghcr.io/yrootlab/onmaru-backend/spring-api",
      "imageTag": "40-char-git-sha",
      "expectedDigest": "sha256:...",
      "deployedDigest": "sha256:...",
      "readinessPath": "/actuator/health"
    },
    {
      "name": "ai-service",
      "image": "ghcr.io/yrootlab/onmaru-backend/ai-service",
      "imageTag": "40-char-git-sha",
      "expectedDigest": "sha256:...",
      "deployedDigest": "sha256:...",
      "readinessPath": "/ready"
    }
  ]
}
```

불변식:

- `tag`는 `vX.Y.Z`, `commitSha`는 full lowercase hex SHA, digest는 `sha256:<hex>`다.
- `environment`는 현재 benchmark scope에서 `staging`만 허용한다.
- 모든 서비스의 `expectedDigest`와 `deployedDigest`가 존재하고 같아야 한다.
- secret, token, credential-bearing URL, cookie, email, user identifier는 금지한다.

## Benchmark evidence manifest

`benchmark/onmaru-backend/<candidate-release>/manifest.json`에 저장한다.

필수 필드는 `benchmarkRunId`, `releaseId`, `baselineReleaseId`, `candidateReleaseId`,
`commitSha`, `imageDigests`, `environment`, `suite`, `toolkit`, `configSha256`, `status`,
`rawUri`, `normalizedUri`, `reportUri`다. `toolkit.version`은 provenance가 확인되기 전
`unverified`를 허용하지만 promotion gate에서는 성공 상태로 취급하지 않는다.

허용 status는 다음 네 가지다.

- `improved`: 비교 조건이 호환되고 정책상 성능이 개선됨
- `unchanged`: 비교 가능하며 tolerance 범위 안
- `regressed`: 비교 가능하며 regression threshold 초과
- `inconclusive`: timeout, cancellation, 누락/무효 artifact, readiness 실패, metric 누락,
  digest/config/runner/fixture/cache/dependency mismatch 등으로 판정 불가

benchmark failure는 원인이 비교 불가라면 `regressed`로 변환하지 않는다.

## Comparison result

비교 결과는 baseline/candidate의 `median`, `p95`, `absoluteDelta`, `relativeDelta`,
CPU/memory 측정값과 모든 run ID를 보존한다. compatibility mismatch의 구체적인 이유와
toolkit command output은 redaction 후 raw/normalized/report artifact로 연결한다.

## Toolkit boundary

W2 adapter가 소비할 논리적 작업은 `release register`, `benchmark suite`, `compare`,
`publish` 네 단계다. 실제 command, flags, JSON schema, exit code는 다음 증거가 추가된
뒤에만 동결한다.

1. 공식 repository 또는 registry URL
2. pinned version과 checksum
3. 설치 명령 및 `--version`, `--help`, `schema` 출력
4. 네 작업의 input/output example과 실패 분류

## Scope boundary

#255는 CI job/Gradle/pytest 병렬화와 일반 전후 benchmark의 실행 최적화를 소유한다.
#334는 release tag에서 staging deployed digest, 직전 성공 baseline, comparison artifact,
promotion gate까지의 release-to-release orchestration을 소유하며 benchmark 분석 알고리즘을
backend에 재구현하지 않는다.
