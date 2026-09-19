---
id: ADR-0010
title: 스크린 속 한옥(K-콘텐츠) 장소 매칭을 사람 검수 없이 출처 기반 자동 게시로 운영한다
status: accepted
date: 2026-09-19
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0002
  - ADR-0006
  - ADR-0007
affected_paths:
  - docs/ai/
  - ai/
  - modules/catalog/
  - apps/spring-api/src/main/java/com/yrootlab/onmaru/web/hanok/
  - docs/specs/backend-requirements/
tags:
  - ai
  - catalog
  - k-content
  - screen-hanok
  - publication
  - gemini
retrospective: false
---

# 스크린 속 한옥(K-콘텐츠) 장소 매칭을 사람 검수 없이 출처 기반 자동 게시로 운영한다

## 맥락 및 문제 설명

FE가 `/hanok` 페이지의 K-컬처 섹션을 "스크린 속 한옥(드라마·영화·K-POP 뮤비 촬영지)" 테마로 개편하면서, 이 테마 피드를 제공하는 백엔드 API를 요청했다. 요구사항은 대한민국·한옥·전통 요소를 나타내는 기존 공공데이터(TourAPI 등)에 이미 등재된 장소 중, 실제로 K-콘텐츠(영화/드라마/뮤직비디오)에 등장한 곳을 식별해 보여주는 것이다. 그러나 TourAPI를 비롯한 기존 카탈로그 소스에는 "이 장소가 어떤 작품에 등장했는지"를 나타내는 필드가 없다. 이 정보는 AI가 별도로 리서치해서 새로 만들어내야 한다.

이 저장소의 기존 AI 파이프라인은 ADR-0002(Spring/FastAPI 역할 분리)와 `docs/ai/application-architecture.md`에 따라 "Gemini는 공급된 candidate/evidence 밖의 사실·장소·이미지를 도입할 수 없다"는 원칙으로 설계되어 있고, 외부 검색 도구 호출도 MVP 범위에서 비활성화되어 있다(ADR-0007의 evidence-bound baseline 원칙과 동일 선상). 이번 기능은 정확히 그 반대 방향, 즉 AI가 스스로 리서치해 새로운 사실 주장(장소-작품 매칭)을 만들어내야 하는 기능이다. 또한 운영 인력 투입 없이 매일 자동으로 갱신되는 완전 자동 파이프라인이 요구된다.

## 의사결정 동인

* 사람 개입 없는 완전 자동 파이프라인이 최우선 목표다(사용자 확인).
* 공개 API에 노출되는 정보의 정확성이 중요하다(사용자 확인).
* 기존 AI 신뢰 경계 원칙(ADR-0002, ADR-0007)과 정면으로 충돌하는 예외를 만들게 된다.
* FE는 출처 불분명한 스크래핑 이미지 대신 이미 검증된 TourAPI 원본 이미지를 재사용하길 원한다.

## 검토한 대안

* AI가 초안(+출처)을 만들고 담당자가 승인해야 게시하는 검수 게이트 방식
* AI가 리서치부터 게시까지 사람 개입 없이 전부 수행하되, 출처(URL) 없는 매칭은 자동 제외하는 방식
* AI 없이 담당자가 직접 확인한 장소만 수동 큐레이션하는 방식(`MonthlyHanokEdition`과 동일 패턴)

## 결정 결과

선택한 대안: **AI가 사람 개입 없이 리서치·게시까지 수행하되, 출처(URL)를 전혀 제시하지 못한 매칭은 자동 제외하는 최소 안전장치를 둔다**, 그 이유는 "사람이 전혀 개입하지 않는 파이프라인"이 이번 기능의 최우선 요구사항이고, 출처 필터링만으로도 근거 없는 게시를 막는 최소한의 기계적 신뢰성을 확보할 수 있기 때문이다.

### 결과 및 영향

* 장점: 담당자 개입 없이 하루 1회 배치로 최신 K-콘텐츠 연계 장소 데이터를 자동 반영한다. 출처 없는 항목은 자동 제외되어 근거 없는 게시를 막는다. 이미지도 스크래핑이 아닌 기존 검증된 TourAPI 이미지를 재사용해 별도의 이미지 신뢰성 문제를 만들지 않는다.
* 단점(수용됨): 출처가 있어도 AI가 장소를 잘못 매칭하거나 내용을 오역·오요약할 위험은 남는다. 이는 ADR-0002 및 `docs/ai/application-architecture.md`의 "Gemini는 공급된 evidence 밖의 사실을 도입할 수 없다"는 기존 원칙에 대한 명시적 예외이며, 이 ADR이 스크린 속 한옥 기능에 한해 그 예외를 승인한다.

### 확인 방법

* 배치 파이프라인 코드에 "출처 URL이 없는 매칭 결과는 quarantine/제외" 로직이 존재하고 테스트로 검증된다.
* Spring/Java 코드가 Gemini 등 LLM provider SDK를 직접 호출하지 않고, FastAPI 내부 계약을 통해서만 리서치를 요청한다(ADR-0002 경계 유지) — 아래 Implementation Constraints로 기계적으로 검증한다.
* 게시 API 응답의 모든 항목에 출처 URL 필드가 채워져 있음을 계약 테스트로 검증한다.

## 대안별 장단점

### AI 초안 + 사람 승인

* 장점, 정확도가 가장 높고 오정보 노출 위험이 사실상 없다.
* 단점, 매일 담당자가 검토해야 하므로 "사람 개입 없는 파이프라인"이라는 요구사항을 충족하지 못한다.

### AI 완전 자동 + 출처 필수 필터 (선택)

* 장점, 운영 부담이 없고 요구사항대로 완전히 자동화된다.
* 단점, 출처가 있어도 오매칭 가능성은 완전히 배제되지 않는다.

### 사람 수동 큐레이션(AI 미사용)

* 장점, 가장 안전하고 기존 `MonthlyHanokEdition` 패턴을 그대로 재사용해 가장 빨리 착수할 수 있다.
* 단점, "AI가 파악해서 자동으로 가공하는 파이프라인"이라는 이번 요청의 핵심 목표를 달성하지 못한다.

## Implementation Constraints

```yaml
constraints:
  - id: no-direct-llm-sdk-in-spring
    kind: forbidden_import
    paths: ["apps/spring-api/**", "modules/**", "adapters/**"]
    pattern: ["com\\.google\\.genai", "google\\.generativeai", "vertexai\\."]
    severity: major
    message: "Spring/Java 코드는 LLM provider SDK를 직접 호출할 수 없다. K-콘텐츠 리서치를 포함한 모든 AI 호출은 FastAPI 내부 계약을 통해서만 수행한다 (ADR-0002 경계 유지)."
```

## 재검토 조건

* 사용자 또는 사내에서 "이 정보가 사실과 다르다"는 오정보 제보가 누적되면, 사람 검수 게이트(AI 초안 + 승인 방식) 도입을 재검토한다.
