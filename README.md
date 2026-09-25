# OnMaru Backend

OnMaru는 비즈니스 API를 담당하는 Java/Spring Boot 애플리케이션과 AI 처리를 담당하는 Python/FastAPI 애플리케이션을 분리해 운영한다. 현재 저장소에는 두 애플리케이션의 실행·테스트 기반과 백엔드 설계 문서가 있다.

## 준비된 개발 환경

| 영역 | 고정 버전 또는 도구 | 위치 |
|---|---|---|
| Java | Java Toolchain 21 | `build.gradle.kts` |
| Spring | Spring Boot 4.1.1 | `gradle/libs.versions.toml` |
| Gradle | Wrapper 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| Python | CPython 3.12 계열 | `ai/.python-version`, `ai/pyproject.toml` |
| Python package | uv와 `uv.lock` | `ai/` |

Gradle은 전역 설치가 필요하지 않다. Python 환경 생성에는 [`uv`](https://docs.astral.sh/uv/getting-started/installation/)가 필요하다.

## 처음 한 번 실행

인터넷이 되는 환경에서 다음 명령으로 빌드 도구와 현재 scaffold 의존성을 받는다.

```bash
./gradlew --no-daemon check

cd ai
uv sync --frozen --all-groups
uv run ruff check .
uv run mypy src tests
uv run pytest
```

이 명령은 Gradle 캐시와 uv 캐시, `ai/.venv`를 현재 장비에 만든다. 캐시와 가상환경은 Git에 커밋하지 않는다.

## 서버 실행

Spring API:

```bash
./gradlew :apps:spring-api:bootRun
curl http://localhost:8080/actuator/health
```

FastAPI AI 서비스:

```bash
cd ai
uv run uvicorn onmaru_ai.main:app --reload --port 8001
curl http://localhost:8001/health
curl http://localhost:8001/ready
```

두 서비스는 현재 DB 자격 증명이나 외부 API 키 없이 실행된다.

## 같은 장비에서 오프라인 검증

온라인 검증을 한 번 완료한 뒤에는 다음 명령으로 캐시에 필요한 항목이 모두 있는지 확인할 수 있다.

```bash
./gradlew --offline --no-daemon check

cd ai
UV_OFFLINE=1 uv sync --frozen --all-groups
UV_OFFLINE=1 uv run ruff check .
UV_OFFLINE=1 uv run mypy src tests
UV_OFFLINE=1 uv run pytest
```

오프라인 보장은 **같은 장비와 사용자 계정의 예열된 캐시**를 전제로 한다. 다른 노트북으로 Git 저장소만 옮기면 캐시가 함께 이동하지 않으므로 인터넷 연결 상태에서 최초 실행을 다시 해야 한다. 후속 Issue에서 새 라이브러리를 추가할 때도 온라인 `check` 또는 `uv sync`를 먼저 실행해야 한다.

## CI 기준선 수집과 비교

CI를 바꾸기 전후의 시간을 비교할 때는, 한 번의 빠르거나 느린 실행으로 결론 내리지 않는다. 같은 커밋과 같은 실행 조건에서 CI를 세 번 완료해 중앙값을 기준선으로 남긴 뒤, 변경 후에도 같은 방식으로 세 번 수집한다. 이렇게 하면 일시적인 GitHub Actions 대기 시간이나 캐시 상태가 판단을 흐리는 일을 줄일 수 있다.

### 1. 비교 가능한 실행 세 번 만들기

`develop`의 같은 커밋에서 CI를 한 번씩 차례로 실행한다. CI workflow에는 동시 실행을 정리하는 설정이 있으므로, 앞선 실행이 끝난 뒤 다음 실행을 시작한다.

```bash
gh workflow run CI --repo YRootLab/OnMaru-backend --ref develop
```

각 실행이 성공한 뒤 Actions 화면에서 run ID 세 개를 기록한다. 서로 다른 커밋, runner 이미지, 의존성 모드, 캐시 상태, Java/Python 버전을 섞으면 비교 대상이 아니다.

### 2. 기준선 artifact 만들기

Actions의 **Collect CI Baseline Evidence** workflow를 `develop`에서 실행하고, 아래 입력을 채운다. workflow가 기본 브랜치에 아직 동기화되지 않아 CLI 목록에 보이지 않을 때는 Actions 화면에서 `develop`을 선택해 실행한다.

| 입력 | 값 |
| --- | --- |
| `run_ids` | 성공한 CI run ID 세 개를 쉼표로 연결한 값 |
| `runner_image` | 예: `ubuntu-latest` |
| `dependency_mode` | 예: `locked` |
| `cache_state` | 예: `unknown` |
| `java_version` | `21` |
| `python_version` | `3.12` |

성공하면 `ci-serial-baseline` artifact에 `serial-baseline.json`과 `summary.md`가 생성된다. artifact 보관 기간은 30일이며, 원시 로그나 서비스 자격 증명은 저장하지 않는다.

### 3. 두 기준선 비교하기

수집한 변경 전·후 `serial-baseline.json`을 내려받은 뒤 아래처럼 실행한다.

```bash
node scripts/benchmark/compare-ci-baseline.mjs \
  --baseline ./baseline-before.json \
  --candidate ./baseline-after.json \
  --json-output ./ci-comparison.json \
  --markdown-output ./ci-comparison.md
```

`ci-comparison.json`은 후속 자동화가 읽는 구조화된 결과이고, `ci-comparison.md`는 PR이나 운영 기록에 바로 붙일 수 있는 요약이다. 비교 도구는 실행 조건이 하나라도 다르면 중단한다. GitHub Actions API만으로 확인할 수 없는 CPU·메모리 수치는 `0`으로 바꾸지 않고 “수집 불가”로 남긴다.

## 환경 분리 및 프로파일 전환 가이드 (Local, Develop, Production)

> **💡 iOS 개발 경험이 있는 분들을 위한 매핑 가이드**
> - **iOS Scheme / Target / Build Configuration (Debug, Staging, Release)** ➡️ Spring Boot의 **`SPRING_PROFILES_ACTIVE`** (`local`, `develop`, `production`) 및 Python의 **`ONMARU_ENV`**
> - **`Secrets.xcconfig` / Plist / Keychain** ➡️ 환경변수(`ONMARU_SECRET_*`) 및 `.env.local`

| 환경 (Stage) | Spring Profile | Secrets Source | DB / 외부 API 동작 | 용도 |
|---|---|---|---|---|
| **Local (기본)** | `local` (default) | `fake` (Mock) | 인메모리 DB, 가짜 외부 API 키로도 오프라인 빌드/테스트 100% 통과 | 로컬 빠른 개발 및 단위/통합 테스트 |
| **Develop** | `develop` | `ENVIRONMENT` | Neon 개발용 DB, 한국관광공사/Odii/Gemini 테스트 키 연동 | PR 검증 및 개발 서버 |
| **Production** | `production` | `ENVIRONMENT` | Neon Production DB (PostGIS), 실전 공공데이터/Gemini API, 자동 동기화 활성화 | 실제 서비스 운영 배포 (Render) |

### 1. 프로파일별 실행 방법

```bash
# 1) 로컬 개발 (외부 키 없이 빠른 실행)
./gradlew :apps:spring-api:bootRun

# 2) 개발/스테이징 환경 실행 (로컬 환경변수 또는 .env.local 주입)
SPRING_PROFILES_ACTIVE=develop ./gradlew :apps:spring-api:bootRun

# 3) 프로덕션 환경 실행 (Render 등의 컨테이너 환경)
SPRING_PROFILES_ACTIVE=production \
ONMARU_SECRETS_SOURCE=ENVIRONMENT \
./gradlew :apps:spring-api:bootRun
```

### 2. 필수 환경변수 목록 (Production / Develop)

| 환경변수 | 설명 |
|---|---|
| `SPRING_PROFILES_ACTIVE` | 활성 프로파일 (`production`, `develop`, `local`) |
| `ONMARU_SECRETS_SOURCE` | 시크릿 소스 (`ENVIRONMENT` 또는 `fake`) |
| `ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT` | 한국관광공사 소리마루(Odii) 오디오 가이드 서비스키 |
| `ONMARU_SECRET_TOURAPI_SERVICE_KEY_CURRENT` | 한국관광공사 TourAPI 국문관광정보 서비스키 |
| `ONMARU_SECRET_GEMINI_API_KEY_CURRENT` | Google Gemini AI API 키 |
| `ONMARU_SECRET_OAUTH_CLIENT_SECRET_CURRENT` | 쿠키 세션 및 카카오 OAuth 서명 비밀키 |
| `ONMARU_SECRET_OTLP_EXPORTER_TOKEN_CURRENT` | 관측성(OTel) 메트릭 전송 토큰 |
| `ONMARU_SECRET_MODERATION_OPERATOR_TOKEN_CURRENT` | 관리자/운영자 API 인증 토큰 (`Bearer` 방식) |
| `ONMARU_DB_URL` | Neon PostgreSQL 접속 JDBC URL |
| `ONMARU_DB_RUNTIME_USER` / `PASSWORD` | DB 접속 계정 및 비밀번호 |

## 문서

백엔드 기획과 구현 기준은 [`docs/README.md`](docs/README.md)에서 시작한다. Spring 실행 세부사항은 [`apps/spring-api/README.md`](apps/spring-api/README.md), FastAPI 실행 세부사항은 [`ai/README.md`](ai/README.md)를 참고한다.
