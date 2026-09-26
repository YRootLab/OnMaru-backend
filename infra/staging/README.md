# Staging release rehearsal

이 디렉터리는 Issue #82의 staging 배포 순서와 credential 경계를 저장한다. hosting은 아직 특정 provider로 고정하지 않고, `release-plan.json`을 배포 adapter가 읽는 입력 계약으로 둔다.

## Flow

1. Spring API와 FastAPI AI service image를 각각 multi-stage Dockerfile로 build한다.
2. image scan이 실패하면 staging 배포를 시작하지 않는다.
3. migration credential로만 migration gate를 실행한다.
4. runtime credential은 DDL 권한 없이 application runtime에만 사용한다.
5. staging smoke가 실패하면 이전 image digest로 rollback한다.

## Credential boundary

- `ONMARU_STAGING_DB_RUNTIME`: runtime DML/read credential, DDL 금지
- `ONMARU_STAGING_DB_MIGRATION`: migration 전용 credential, DDL 허용
- `ONMARU_STAGING_DB_READONLY`: smoke/read-only 점검 credential, DDL 금지
- `ONMARU_STAGING_DB_BACKUP`: backup/restore drill credential, runtime과 분리

실제 값은 GitHub environment secret 또는 외부 secret manager에만 저장한다.

## GitHub environment inputs

`.github/workflows/deploy.yml`은 `staging` environment에서 다음 값을 요구한다.

- `STAGING_SPRING_URL`: Spring API staging base URL
- `STAGING_AI_URL`: FastAPI AI service staging base URL
- `PREVIOUS_SPRING_DIGEST`: rollback 대상 Spring API image digest
- `PREVIOUS_AI_DIGEST`: rollback 대상 FastAPI AI image digest
- `ONMARU_STAGING_DB_MIGRATION_USER`, `ONMARU_STAGING_DB_MIGRATION_PASSWORD`: migration gate 전용
- `ONMARU_STAGING_DB_RUNTIME_USER`, `ONMARU_STAGING_DB_RUNTIME_PASSWORD`: runtime 전용

DataLab staging smoke workflow는 같은 `staging` environment에서 다음 값을 추가로 요구한다.

- `ONMARU_DATALAB_OPERATIONS_TOKEN`: 보호된 수동 수집 endpoint 호출용 token
- `ONMARU_DATALAB_VISITOR_SERVICE_KEY`: 실제 공공데이터 DataLab 호출에 쓰는 service key
- `ONMARU_STAGING_READONLY_DB_URL`: `psql`이 사용할 read-only PostgreSQL URL

배포된 Spring runtime에는 동일한 operations token을
`ONMARU_SECRET_DATALAB_OPERATIONS_TOKEN_CURRENT`로 주입한다. 모든 값은 GitHub
environment secret 또는 secret manager에만 두며 workflow artifact에는 원문을 남기지 않는다.
Spring image는 build 시 `ONMARU_BUILD_GIT_SHA`를 build-info에 고정한다. DataLab smoke는
operations 응답의 이 값이 triggering `Staging Deploy` SHA와 다르면 #392를 닫지 않는다.
