# 공개 헬스체크 API 설계

## 배경

현재 Spring Boot 애플리케이션은 `/actuator/health`를 제공하지만, 이 경로는 Actuator가 관리하므로 Swagger의 일반 API 목록에 표시되지 않는다. 운영자와 프론트엔드가 Swagger에서 직접 호출할 수 있고 Render 인스턴스의 기동을 유도할 수 있는 공개 liveness API가 필요하다.

## 목표와 범위

- `GET /api/v1/health`를 인증 없이 제공한다.
- 정상 기동된 애플리케이션은 HTTP 200과 `{"status":"UP"}`를 반환한다.
- OpenAPI 문서와 Swagger UI에서 엔드포인트 및 응답 schema를 확인할 수 있게 한다.
- 응답 과정에서 PostgreSQL, TourAPI 등 외부 의존성을 호출하지 않는다.

DB 연결 상태까지 검사하는 readiness API, 주기적인 외부 ping, Render sleep 방지 인프라는 이번 범위에 포함하지 않는다. Render가 sleep 상태라면 최초 요청이 wake-up을 시작하며 cold start 지연은 피할 수 없다.

## 구조

`apps/spring-api`의 web 계층에 작은 전용 controller와 불변 응답 DTO를 둔다. Controller는 고정된 `UP` 응답만 반환하며 service나 repository에 의존하지 않는다. 기존 `/actuator/health`는 플랫폼용 상태 확인 경로로 그대로 유지한다.

OpenAPI annotation으로 operation 요약과 200 응답을 기술한다. 별도 security scheme을 요구하지 않는 공개 endpoint로 유지한다.

## 오류와 보안

애플리케이션이 요청을 처리할 수 있는 상태라면 외부 시스템 상태와 관계없이 200을 반환한다. 애플리케이션 자체가 기동하지 못했거나 Render가 cold start 중이면 플랫폼 수준 지연 또는 오류가 발생할 수 있으며, 이를 API 내부에서 숨기지 않는다.

응답에는 빌드 버전, 환경 변수, DB 상태 등 내부 정보를 포함하지 않는다.

## 검증

- MockMvc로 `/api/v1/health`의 200 및 정확한 JSON 응답을 검증한다.
- `/v3/api-docs`에 `/api/v1/health`가 노출되는지 검증한다.
- 테스트 구성이 DB나 외부 API 없이 실행되는지 확인한다.

관련 이슈: #477
