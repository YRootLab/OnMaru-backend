# Benchmark evidence contracts

## 계약 상태

`release metadata`와 evidence 필드는 현재 저장소 convention에서 도출한 v1 제안 계약이다.
`pipeline-toolkit` CLI 계약은 공식 provenance와 `--help/schema` 출력이 확보되기 전까지
`unverified`다. 이 상태에서 adapter가 임의의 toolkit command를 호출해서는 안 된다.

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
