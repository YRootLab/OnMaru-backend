---
id: ADR-0011
title: Spring Boot startup에서 Flyway가 schema migration을 소유한다
status: proposed
date: 2026-09-20
locale: ko
decision_makers:
  - 사용자
  - OnMaru Backend contributors
related:
  - ADR-0004
affected_paths:
  - apps/spring-api/build.gradle.kts
  - apps/spring-api/src/main/resources/application.yaml
  - apps/spring-api/src/main/resources/db/migration/
  - gradle/libs.versions.toml
tags:
  - spring-boot
  - flyway
  - postgresql
  - database-migration
  - operations
retrospective: false
---

## 맥락 및 문제 설명

Production에서 Spring Boot 애플리케이션은 기동했지만 `onmaru.discovery_runs`와 `flyway_schema_history`가 생성되지 않아 `JourneyRunSweeper`가 `relation does not exist` 오류를 반복했다. migration SQL과 Flyway 엔진이 bootJar에 포함되어 있어도 Spring Boot 4의 Flyway auto-configuration 모듈이 runtime classpath에 없으면 startup migration이 실행되지 않는다.

## Confirmed Evidence

* Production Neon에서 `to_regclass('onmaru.discovery_runs')`가 `NULL`이었다.
* Production Neon에서 `flyway_schema_history`와 `discovery_runs`가 모두 존재하지 않았다.
* 저장소의 `V006`은 `onmaru.discovery_runs`를 생성하고 `V016`은 누락 상태를 복구한다.
* 기존 production runtime classpath에는 Flyway core와 PostgreSQL extension만 있었고 `spring-boot-flyway`는 없었다.
* Spring Boot 4.1.1의 `spring-boot-flyway`는 `FlywayAutoConfiguration`과 `FlywayMigrationInitializer`를 제공한다.

## 검토한 대안

1. **Spring Boot `spring-boot-flyway` auto-configuration 사용**
2. Render deploy 전에 별도 migration command를 운영
3. Neon SQL Editor에서 수동 DDL 또는 migration 실행

## 결정 결과

선택한 대안: **Spring Boot startup에서 Flyway가 schema migration을 소유한다.**

`spring-boot-flyway`를 애플리케이션 runtime에 포함하고, Spring Boot dependency management가 관리하는 Flyway core와 PostgreSQL database extension을 사용한다. `application.yaml`의 datasource와 migration credential fallback을 유지하며, production schema는 수동 DDL이 아니라 정상 배포 startup에서 forward-only migration으로 변경한다.

## 결과 및 영향

* 장점: bootJar, application startup, migration history가 같은 배포 산출물로 연결되어 schema drift를 조기에 발견할 수 있다.
* 장점: migration contract와 Journey migration 테스트로 V001부터 최신 migration까지 재현할 수 있다.
* 단점: migration 권한과 DB 연결 오류가 있으면 애플리케이션 startup이 실패할 수 있으므로 Render 환경변수와 migration role을 운영해야 한다.

## Implementation Constraints

* `spring-boot-flyway`는 Spring Boot version catalog/dependency management를 통해 버전을 결정해야 하며 Flyway core 버전을 임의로 하드코딩하지 않는다.
* production DB에 수동 DDL을 실행해 migration history를 우회하지 않는다.
* 모든 migration은 `apps/spring-api/src/main/resources/db/migration/baseline/`의 forward-only 순서를 따른다.
* 배포 후 `flyway_schema_history`와 핵심 relation을 read-only SQL로 검증한다.

## 재검토 조건

* migration 실행 시간이 배포 SLO를 지속적으로 초과한다.
* 무중단 배포 또는 별도 release pipeline이 startup migration과 충돌한다.
* schema 변경 권한을 애플리케이션과 분리해야 하는 보안·운영 요구가 확정된다.
