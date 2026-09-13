# Spring·FastAPI 개발 환경 초기 구축 설계

작성일: 2026-09-14  
대상 Issue: [#63](https://github.com/YRootLab/OnMaru-backend/issues/63), [#64](https://github.com/YRootLab/OnMaru-backend/issues/64)

## 목적

집의 안정적인 네트워크에서 Spring과 FastAPI 프로젝트, 빌드 도구, 의존성을 먼저 내려받고 검증한다. 이후 학교 네트워크에서는 저장소를 열어 바로 개발하고, 이미 내려받은 로컬 캐시가 있으면 제한적인 오프라인 빌드와 테스트도 수행할 수 있어야 한다.

## 선택한 방식

생성 결과와 버전 정보는 저장소에 고정하고, 다운로드된 의존성 캐시는 개발 장비에 예열한다. Gradle과 Python 패키지 캐시 자체는 저장소에 커밋하지 않는다.

Docker 이미지에 개발 도구 전체를 넣는 방식은 PostgreSQL 로컬 환경을 다루는 후속 Issue #65와 책임이 겹치므로 이번 범위에서 제외한다. 생성 파일만 커밋하고 의존성을 나중에 받는 방식은 학교에서의 첫 빌드가 네트워크에 의존하므로 채택하지 않는다.

## Spring 구조

- Java Toolchain은 21로 고정한다.
- Spring Boot는 현재 기획 문서와 공식 Initializr의 안정 버전인 4.1.1로 고정한다.
- Kotlin Gradle DSL과 저장소에 커밋한 Gradle Wrapper를 사용한다. 전역 Gradle 설치를 요구하지 않는다.
- 루트는 멀티프로젝트 빌드를 소유하고, 실행 애플리케이션은 `apps/spring-api`에 둔다.
- 최초 의존성은 Spring Web MVC와 Actuator로 제한한다. 업무 API, DB, 인증은 후속 Issue가 소유한다.
- Spring context smoke test와 Actuator health HTTP test를 먼저 실패시키고, 이를 통과시키는 최소 애플리케이션을 구현한다.

## FastAPI 구조

- Python 3.12 minor 계열을 사용하며 `pyproject.toml`의 `requires-python`과 버전 파일로 고정한다.
- 패키지와 Python 환경 관리는 `uv`로 통일하고 `uv.lock`을 커밋한다.
- 소스는 `ai/src/onmaru_ai`, 테스트는 `ai/tests`에 둔다.
- 런타임 의존성은 FastAPI와 Uvicorn으로 제한한다. 개발 의존성은 pytest, HTTPX, Ruff, mypy로 분리한다.
- `/health`는 프로세스 생존을, `/ready`는 요청 수신 준비 상태를 나타낸다. 현재 외부 의존성이 없으므로 두 응답은 독립 endpoint와 독립 테스트로 유지한다.
- ASGI endpoint test를 먼저 실패시키고, 이를 통과시키는 app factory와 endpoint를 구현한다.

## 재현성과 오프라인 대비

- Gradle Wrapper 실행 파일, JAR, properties와 정확한 배포판 검증값을 커밋한다.
- Gradle dependency locking을 활성화하고 동적 버전을 사용하지 않는다.
- `uv.lock`과 안전한 예제 환경 파일은 커밋하되 `.venv`, Gradle 캐시, build 결과, `.env`, secret은 제외한다.
- 온라인 상태에서 Spring check와 Python sync·lint·typecheck·test를 실행해 필요한 배포판과 패키지를 로컬 캐시에 받는다.
- 온라인 검증 뒤 Gradle offline check와 uv offline sync/test를 실행한다. 이 검증은 같은 장비의 예열된 캐시 사용을 보장하며, 다른 장비로 캐시가 자동 이동한다는 의미는 아니다.

## 개발 도구 사용

IntelliJ IDEA에서는 저장소 루트의 `settings.gradle.kts`를 Gradle 프로젝트로 연다. Spring Boot 실행 대상은 `apps/spring-api`의 main class다.

VS Code에서는 저장소 전체 또는 `ai/`를 열고 `ai/.venv`의 Python interpreter를 선택한다. IDE 설정이 없어도 문서에 기록된 `uv run` 명령으로 동일하게 실행·검증할 수 있어야 한다.

## 오류 처리와 경계

- Spring과 FastAPI는 DB 자격 증명이나 외부 API 키 없이 시작해야 한다.
- Health endpoint는 외부 서비스 호출을 수행하지 않는다.
- Readiness에 외부 의존성이 추가되는 시점에는 해당 후속 Issue가 상태 판정과 실패 응답을 확장한다.
- 로컬 JDK 또는 Python 조건이 맞지 않으면 빌드 도구가 요구 버전을 명시적으로 안내해야 한다.

## 완료 조건

1. 깨끗한 checkout에서 전역 Gradle 없이 Wrapper 버전 확인과 전체 Spring check가 통과한다.
2. Spring context와 Actuator health smoke test가 통과한다.
3. `uv sync --frozen` 후 Ruff, mypy, pytest가 모두 통과한다.
4. FastAPI health와 readiness가 별도 endpoint로 검증된다.
5. 온라인 실행으로 캐시를 예열한 뒤 같은 장비에서 Gradle과 uv의 offline 검증이 통과한다.
6. IntelliJ와 VS Code 실행 방법 및 터미널 대체 명령이 README에 기록된다.

## 범위 밖

PostgreSQL/PostGIS, Flyway, Spring Security, 실제 업무 endpoint, AI 모델 호출, Docker Compose와 배포 이미지는 이번 작업에 포함하지 않는다.
