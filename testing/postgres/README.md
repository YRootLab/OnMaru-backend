# PostgreSQL/PostGIS test support

Spring 통합 테스트의 canonical helper는 `apps/spring-api/src/test/java/com/yrootlab/onmaru/testing/postgres/PostgresTestDatabase.java`이다.

현재 보장 범위:

- Testcontainers PostgreSQL은 `postgis/postgis:17-3.5-alpine` 이미지를 사용한다.
- `PostGIS_Version()` 호출로 extension 사용 가능성을 검증한다.
- `reset(Connection)`은 `public` schema를 재생성하고 `postgis` extension을 다시 생성해 반복 실행 시 깨끗한 DB를 보장한다.
