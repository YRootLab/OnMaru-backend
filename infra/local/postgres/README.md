# PostgreSQL/PostGIS local runtime

Issue #65의 로컬 통합 테스트 기준 DB이다. 운영 hosting 선택은 다루지 않고, 로컬 개발과 container smoke test에서 같은 PostGIS 전제를 공유한다.

## 실행

```bash
docker compose -f infra/local/postgres/compose.yaml up -d
```

기본 접속값은 다음과 같다.

```text
host=localhost
port=5432
database=onmaru
user=onmaru
password=onmaru-local
```

포트를 바꾸려면 `ONMARU_POSTGRES_PORT`를 지정한다.

## Readiness

compose는 초기화 SQL로 `postgis` extension을 생성한다. healthcheck는 `pg_isready`와 `pg_extension`의 `postgis` 등록 여부를 함께 확인한다. 애플리케이션 통합 테스트는 in-memory DB를 쓰지 않고 Testcontainers의 `postgis/postgis:17-3.5-alpine` 이미지를 사용한다.

## Test DB reset

반복 테스트는 `PostgresTestDatabase.reset(Connection)`으로 `public` schema를 drop/create하고 `postgis` extension을 다시 보장한다. 테스트 데이터는 각 smoke 실행 사이에 남지 않아야 한다.
