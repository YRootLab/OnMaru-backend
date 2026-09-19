---
id: ADR-0003
title: Spring 모듈 경계를 consumer-owned port와 제한된 bridge로 보호한다
status: accepted
date: 2026-09-13
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0002
affected_paths:
  - docs/architecture/
  - docs/spring/
  - docs/ai/
  - spring/
  - ai/
tags:
  - modular-monolith
  - boundaries
  - architecture
retrospective: false
---

# Spring 모듈 경계를 consumer-owned port와 제한된 bridge로 보호한다

## 맥락 및 문제 설명

Spring 비즈니스 API는 identity, catalog, discovery, journey, community의 변경 속도와 책임이 다르다. 구현 초기부터 core가 Spring/JPA/HTTP 또는 다른 core의 내부 구현을 직접 참조하면 순환 의존, 테스트 곤란, 기능별 변경 전파가 생긴다. 팀 규모와 배포 요구는 아직 별도 서비스를 정당화하지 않는다.

## 검토한 대안

1. 순수 core와 consumer-owned port, app composition bridge를 사용한다.
2. 하나의 feature package 안에서 framework와 domain을 함께 둔다.
3. 모든 feature와 계층을 별도 Gradle 모듈로 분리한다.

## 결정 결과

선택한 대안: **순수 core와 consumer-owned port, 제한된 app bridge를 사용하는 modular monolith**, 그 이유는 단일 배포의 단순성을 유지하면서 core 간 직접 의존과 framework 침투를 컴파일·테스트 경계로 제한하기 때문이다.

## 결과 및 영향

* 장점: `journey → discovery → catalog`, `community → catalog`의 허용된 호출 방향과 context 소유권을 명시할 수 있다.
* 단점: bridge DTO 변환과 app orchestration 코드가 추가되고 persistence 기술 모듈의 package 규율이 필요하다.

## 구현 제약

```yaml
constraints:
  - id: core-no-framework-imports
    kind: forbidden_import
    paths: ["spring/modules/*/src/main/java/**"]
    pattern: ["import org\\.springframework", "import jakarta\\.persistence", "import reactor\\."]
    severity: major
    message: "Core must not import Spring, persistence, or Reactor types."
```

## 확인 방법

Gradle dependency graph, ArchUnit 또는 Spring Modulith test로 core 간 직접 import 0, core의 framework import 0, 허용 bridge 호출 방향을 검증한다. 현재는 구현 전 문서 설계이므로 이 검증이 아직 실행됐다고 주장하지 않는다.

## 재검토 조건

* context별 독립 배포·독립 소유팀·독립 scale-out 요구가 반복적으로 확인된다.
* persistence package 경계가 반복적으로 깨져 별도 저장소 또는 서비스 분리가 더 낮은 비용이 된다.
