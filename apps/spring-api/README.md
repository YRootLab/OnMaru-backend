# Spring API 개발

Java 기본 package와 Gradle group은 `com.yrootlab.onmaru`다. `src/main/java`는 source root이고 실제 package 경로는 그 아래 `com/yrootlab/onmaru`부터 시작한다.

## IntelliJ IDEA

1. IntelliJ에서 하위 모듈만 따로 열지 말고 저장소 루트의 `settings.gradle.kts`를 연다.
2. Gradle 설정의 배포판은 `Wrapper`를, Gradle JVM은 Java 21을 선택한다.
3. Gradle 동기화가 끝나면 `com.yrootlab.onmaru.OnMaruApplication`의 `main`을 실행한다.
4. `http://localhost:8080/actuator/health`에서 `{"status":"UP"}` 응답을 확인한다.

IntelliJ의 유료 Ultimate 기능 없이도 Gradle과 Java 실행 구성으로 개발할 수 있다. IDE가 아닌 터미널에서는 저장소 루트에서 다음 명령을 사용한다.

```bash
./gradlew :apps:spring-api:bootRun
./gradlew :apps:spring-api:test
./gradlew check
```

## 현재 범위

이 모듈은 Web MVC와 Actuator만 가진다. PostgreSQL, Flyway, Security, 외부 관광 API adapter와 업무 endpoint는 각각의 후속 Issue에서 추가한다.
