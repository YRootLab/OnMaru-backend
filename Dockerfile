FROM eclipse-temurin:21-jdk-alpine AS builder
ARG ONMARU_BUILD_GIT_SHA=unknown
ENV ONMARU_BUILD_GIT_SHA=${ONMARU_BUILD_GIT_SHA}
WORKDIR /workspace

COPY gradlew gradlew
COPY gradle gradle
COPY settings.gradle.kts build.gradle.kts gradle.properties* settings-gradle.lockfile* ./
COPY build-logic build-logic
COPY adapters adapters
COPY modules modules
COPY apps apps

RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :apps:spring-api:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine AS runner
WORKDIR /app

RUN apk upgrade --no-cache \
    && apk add --no-cache curl \
    && addgroup -S onmaru \
    && adduser -S onmaru -G onmaru

COPY --from=builder --chown=onmaru:onmaru /workspace/apps/spring-api/build/libs/*.jar app.jar

USER onmaru
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS http://127.0.0.1:8080/actuator/health >/dev/null || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
