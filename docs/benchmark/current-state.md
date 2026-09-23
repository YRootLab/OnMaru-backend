# Release-to-release benchmark current state

## 조사 범위

Issue #335의 Wave 0 조사 결과다. 이 문서는 현재 저장소의 사실과 아직 검증되지 않은
`pipeline-toolkit` 가정을 분리한다. 조사 기준일은 2026-09-23이다.

## 현재 구조

| 영역 | 현재 상태 | 근거 |
| --- | --- | --- |
| Runtime | Gradle 멀티모듈 Spring Boot API와 `ai/` FastAPI 서비스 | `settings.gradle.kts`, `ai/README.md` |
| CI | `.github/workflows/ci.yml`의 `verify` job에서 Gradle, Node, 계약 검증, pytest, lint/typecheck 실행 | `.github/workflows/ci.yml` |
| Deployment | `master` push 또는 수동 실행. Spring/AI image build·scan, migration gate, staging smoke, rollback 단계가 존재 | `.github/workflows/deploy.yml` |
| Release identity | image tag는 commit SHA이며 release tag와 연결되지 않음 | `.github/workflows/deploy.yml`의 `github.sha` tag |
| Release trigger | Release Please와 staging deploy는 `master` push를 기준으로 함. benchmark trigger는 없음 | `.github/workflows/release-please.yml`, `.github/workflows/deploy.yml` |
| Readiness | Spring `/actuator/health`, AI `/ready` smoke convention | `.github/workflows/deploy.yml`, `infra/staging/release-plan.json` |
| Observability | health/readiness와 runtime observability 설계는 있으나 benchmark report 연결은 없음 | `docs/operations/runtime-and-reliability.md` |
| Evidence storage | release evidence 디렉터리는 있으나 benchmark raw/normalized/comparison artifact 계약은 없음 | `docs/operations/release-evidence/` |

`infra/staging/release-plan.json`에는 `deploy-spring-api`와 `deploy-ai-service`가
순서로 기록되어 있지만 현재 deploy workflow에는 해당 job이 없다. 따라서 현재 workflow가
실제로 새 digest를 staging에 배포하는지는 확인되지 않았으며, W3에서 별도 검증해야 한다.

## 실행 진입점

- Spring: `./gradlew :<module>:test --no-daemon`
- FastAPI: `cd ai && uv run pytest`, `uv run ruff check`, `uv run mypy`
- Node 계약/스크립트: `node --test scripts/test/*.test.mjs`
- 저장소 계약: `bash scripts/verify-contracts`
- 배포 convention: `node --test scripts/test/deploy-pipeline.test.mjs`

## pipeline-toolkit 검증 결과

다음 명령은 모두 현재 환경에서 실패했다. binary, Python module, npm dependency,
repository-local implementation 모두 확인되지 않았다.

```text
pipeline-toolkit --version                 # exit 127: command not found
pipeline-toolkit --help                    # exit 127: command not found
pipeline-toolkit schema                    # exit 127: command not found
pipeline-toolkit release register --help  # exit 127: command not found
pipeline-toolkit benchmark suite --help   # exit 127: command not found
pipeline-toolkit compare --help           # exit 127: command not found
pipeline-toolkit publish --help           # exit 127: command not found
python3 -m pipeline_toolkit --version      # No module named pipeline_toolkit
```

동명 PyPI/crates.io 계열 라이브러리는 요구된 `release register`, `benchmark suite`,
`compare`, `publish` 계약의 provenance가 아니므로 dependency로 채택하지 않는다.

## 리스크와 미정 사항

1. toolkit 공식 repository/registry, binary/package 이름, pinned version/checksum이 없다.
2. toolkit의 input/output schema, exit code, timeout/cancellation, artifact publish 방식이 없다.
3. release plan의 deploy job과 실제 workflow가 불일치한다.
4. #255는 CI 병렬화와 일반 benchmark를 다루고, #334는 release tag·image digest·staging
   기준의 release-to-release 비교만 다룬다.

W2는 위 provenance가 확정될 때까지 실제 CLI adapter를 구현하면 안 된다. W1은 이 문서의
검증 가능한 metadata/evidence 필드를 사용하되 toolkit version은 미검증 상태를 허용해야 한다.
