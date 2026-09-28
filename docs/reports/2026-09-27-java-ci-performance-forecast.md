# Java CI 단축 가능성 보고서: 무엇을 바꾸면 얼마나 줄어드는가

- 상태: **예측 보고서 — 구현 성과가 아님**
- 기준일: 2026-09-27
- 관련 이슈: #411
- 범위: OnMaru Backend의 Pull Request CI, Java/Gradle 테스트, `develop` 이후의 전체 검증

## 결론

현재 CI의 병목은 Java 모듈 테스트가 순서대로 실행되는 문제가 아니다. Gradle은 한 Java runner 안에서 여러 모듈의 컴파일과 테스트를 함께 처리하고 있다. 가장 긴 구간은 모든 도메인과 adapter를 조립하는 `:apps:spring-api:test`다.

따라서 작은 도메인 변경의 PR에서는 **변경 모듈 테스트와 빠른 Spring 경계 테스트만 실행**하고, 무거운 PostgreSQL/Testcontainers 통합 테스트는 `develop`, `master`, 릴리스, 야간 검증에서 전부 실행하도록 나누는 것이 가장 높은 단축 가능성을 가진다.

이 방식이 실제로 유지될 최소 판단선은 단일 모듈 PR의 중앙값 **45% 단축**이다. 이 선을 넘지 못하면 선택 실행 정책을 유지하지 않거나 축소한다.

## 이미 확인된 수치와 관찰

동일한 애플리케이션 소스에서 직렬 CI와 공유 workspace fan-out CI를 각각 세 번 실행한 결과, 중앙값은 7분 54초에서 6분 55초로 59초, **12.4%** 줄었다. 이는 예측이 아니라 완료된 CI 구조 개선의 측정값이다. 다만 Java 전체 테스트는 계속 실행했으므로 작은 모듈 PR의 대기 시간은 여전히 Java lane이 결정한다.

| 근거 | 관찰 | 해석 |
| --- | ---: | --- |
| 직렬 CI 중앙값 | 7분 54초 | fan-out 적용 전, 동일 소스 반복 실행 |
| 현 CI 중앙값 | 6분 55초 | Java·계약·AI·hygiene을 병렬 시작한 뒤의 반복 실행 |
| 전체 구조 개선 | 12.4% | Java lane을 없애지 않고 독립 lane 대기 시간을 숨긴 효과 |
| 최근 Java Gradle step | 5분 13초 | Audio 변경 PR에서도 모든 Java 테스트를 실행한 시간 |
| 최근 Spring API test 구간 | 약 3분 53초 이하 | task 시작부터 `BUILD SUCCESSFUL`까지의 상한; Java lane의 주 병목 |

최근 Audio 변경 PR의 Java 로그에서 `:adapters:tourism-api:test`, 각 `modules:*:test`는 16:10:35~16:10:44 UTC에 완료됐고, `:apps:spring-api:test`는 16:11:17 UTC에 시작해 build가 16:15:10 UTC에 끝났다. 모듈 테스트를 별도 GitHub Actions runner로 쪼개면, 이 짧은 구간마다 Gradle 초기화·JDK/cache 복원·의존성 해석을 반복하게 된다. 실제 과거의 module-per-runner shadow benchmark도 중앙값 약 9분 49초로 더 느렸다.

원본 실행: [fan-out 병합 후 CI](https://github.com/YRootLab/OnMaru-backend/actions/runs/36223042483), [Audio 변경 PR CI](https://github.com/YRootLab/OnMaru-backend/actions/runs/36254456746), [module-per-runner shadow benchmark](https://github.com/YRootLab/OnMaru-backend/actions/runs/36211314573).

## 예측 모델과 신뢰 수준

아래 수치는 약속이 아니라, 현재 실행 로그에서 제거 가능한 작업과 남는 작업을 분리한 범위 예측이다. 실제 비교는 같은 SHA·runner·명령 조건에서 성공한 실행의 중앙값으로만 확정한다.

| 대상 | 현재 관찰 범위 | 예측 범위 | 단축 예측 | 신뢰 | 전제 |
| --- | ---: | ---: | ---: | --- | --- |
| 단일 도메인 모듈 PR 전체 CI | 5분 40초~6분 20초 | 1분 50초~3분 10초 | 45~65% | 중간 | 선택 모듈 + 빠른 Spring 경계 테스트가 90초 안에 끝남 |
| 단일 도메인 모듈 Java lane | 5분 10초~5분 20초 | 1분 20초~2분 30초 | 50~70% | 중간 | Testcontainers 통합 테스트를 PR lane에서 제외하고, 전체 검증으로 이동 |
| 앱·JDBC·공용 모듈·Gradle 변경 PR | 5~6분 | 5~6분 | 0~10% | 높음 | 안전상 전체 Java 테스트를 유지 |
| 전체 Spring 통합 테스트 | 약 3분 53초 이하 | 미확정 | 20~40% 가능성 | 낮음 | Testcontainers 공유·격리·class별 병목 계측이 먼저 필요 |

단일 모듈 PR의 예측은 Java lane에서 약 3분 이상의 Spring 통합 테스트를 제거할 수 있다는 관찰을 기반으로 한다. 반대로 Spring 빠른 경계 테스트의 실제 시간은 아직 별도 suite로 기록되지 않았으므로, 1분 20초라는 하한을 성과로 단정하지 않는다.

## 권장 실행 구조

```text
PR의 Catalog 변경
  → Catalog 단위 테스트
  → Spring 빠른 경계 테스트
  → contract / hygiene 병렬 검증
  → verify

develop · master · release · nightly
  → 모든 모듈 테스트
  → Spring 통합 + Testcontainers 테스트
  → 배포 전 검증
```

경로 선택기는 fail-closed여야 한다. `apps/spring-api`, `adapters/persistence-jdbc`, `modules/shared-web`, Gradle 설정, workflow, migration, 여러 모듈 동시 변경은 선택 실행 대신 전체 Java 테스트로 되돌린다. `apps:spring-api`가 모든 모듈을 의존하기 때문에 이 fallback은 선택 사항이 아니라 안전 장치다.

## Testcontainers를 바로 병렬화하지 않는 이유

`apps:spring-api`에는 PostgreSQL/PostGIS `GenericContainer`를 직접 시작하는 테스트 클래스가 다수 있다. 테스트 fork 수만 올리면 이미지 pull, Docker CPU·메모리, port, migration과 DB 상태가 경쟁할 수 있다. 빨라 보이는 한 번의 실행 대신 flaky test와 느린 p95가 생길 수 있다.

전체 통합 테스트를 더 줄일 후보는 다음 순서로만 진행한다.

1. JUnit XML에서 테스트 클래스별 시간과 실패 상태를 안전한 집계 artifact로 남긴다.
2. 가장 느린 container 테스트가 실제로 컨테이너 기동·migration을 중복하는지 확인한다.
3. 테스트 데이터·schema·port 격리 규칙을 만든다.
4. 공유 컨테이너 또는 제한된 fork 수를 한 변수씩 시험한다.
5. 성공률과 p95가 악화되면 변경을 채택하지 않는다.

즉, Testcontainers 개선은 20~40%의 가능성이 있지만, 현재 증거만으로 구현을 시작할 수준의 확정 예측은 아니다.

## CD에 대한 판단

PR 병목은 CI다. Staging Deploy와 Release Benchmark Gate는 별도 workflow이며 PR의 required `verify` 대기 시간을 직접 결정하지 않는다. 따라서 첫 번째 개선은 CI 선택 실행에 집중한다. 이후 CD에서는 Spring/AI 이미지의 변경 경로별 선택 빌드, Buildx cache, migration gate의 중복 제거를 별도 측정 대상으로 다룬다. 현재 CD에 대해서는 성공 실행을 같은 조건으로 비교한 근거가 부족하므로 단축률을 예측하지 않는다.

## 채택·중단 기준

| 변경 | 채택 기준 | 중단 또는 rollback 기준 |
| --- | --- | --- |
| 모듈 선택 PR Java lane | 비교 가능한 성공 실행 중앙값 45% 이상 단축 | 단축 45% 미만 또는 전체 fallback 누락 |
| 빠른 Spring 경계 suite | 앱·DB·공용 변경은 반드시 전체 suite 실행 | 누락된 통합 결함 또는 테스트 분류 모호성 |
| Testcontainers 공유/병렬화 | 중앙값 개선과 성공률·p95 유지 | flaky 증가, resource contention, 상태 누수 |
| Gradle configuration cache | 호환성 확인 후 중앙값 개선 | cache invalidation, 비결정적 결과, 설정 오류 |

## 구현 순서

1. Spring 테스트를 `fast-pr`와 `integration`으로 명시적으로 태그·task 분리한다.
2. 변경 경로에서 영향 모듈 Gradle task 집합을 계산하는 선택기를 만든다.
3. 공용·앱·DB·Gradle 변경에는 전체 Java task를 선택하는 fixture 테스트를 추가한다.
4. JUnit 시간 요약과 선택·fallback 이유를 artifact 및 CI summary로 발행한다.
5. 단일 모듈 PR에서 45% 단축 기준을 통과할 때만 정책을 required CI로 유지한다.

이 순서는 빠른 PR 피드백과 배포 전의 전체 검증을 함께 보장한다. 중요한 검증을 삭제하는 것이 아니라, 같은 검증을 변경 위험도에 맞는 시점으로 배치하는 방식이다.
