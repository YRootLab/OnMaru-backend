# Flyway baseline migration

실행 migration SQL은 Spring Boot classpath 기준인 `apps/spring-api/src/main/resources/db/migration/baseline`에 둔다.

이 디렉터리는 Issue #67의 기대 touch point를 보존하고, version/checksum 예약 정책은 `db/migration/registry/migrations.json`에서 관리한다.
