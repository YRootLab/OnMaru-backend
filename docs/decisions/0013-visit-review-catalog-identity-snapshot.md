---
id: ADR-0013
title: VisitReview는 Catalog 내부 UUID를 참조하고 작성 시점의 공개 장소 snapshot을 보존한다
status: superseded
date: 2026-09-24
locale: ko
decision_makers:
  - 사용자
  - OnMaru Backend contributors
related:
  - ADR-0003
  - ADR-0004
  - ADR-0006
  - ADR-0012
affected_paths:
  - modules/catalog/
  - modules/community/
  - adapters/persistence-jdbc/
  - apps/spring-api/src/main/resources/db/migration/
  - apps/spring-api/src/main/java/com/yrootlab/onmaru/web/review/
tags:
  - community
  - catalog
  - postgresql
  - visit-review
  - data-ownership
retrospective: false
superseded_by: ADR-0014
---

# VisitReview는 Catalog 내부 UUID를 참조하고 작성 시점의 공개 장소 snapshot을 보존한다

## 맥락 및 문제 설명

공개 API의 VisitReview는 안정적인 문자열 `placeId`, 장소명, 행정구역 코드, 좌표를 반환하지만 기존 review table은 Catalog UUID FK만 보관한다. 공개 문자열 ID와 내부 UUID mapping이 없으면 production write의 FK를 만족할 수 없고, 활성 Catalog revision만 매번 조인하면 장소명이 변경되거나 비공개·삭제된 뒤 과거 후기를 같은 모습으로 조회할 수 없다.

좋아요·신고·moderation은 후기와 같은 PostgreSQL transaction 경계에서 referential integrity와 중복 방지를 보장해야 한다. 최대 50개 목록에서 항목별 Catalog 재조회(N+1)를 만들지 않아야 한다.

## 검토한 대안

1. Catalog에 stable public-place-id → internal UUID mapping을 두고, VisitReview에 UUID FK와 작성 시점 공개 snapshot을 함께 저장한다.
2. VisitReview에는 UUID FK만 저장하고 모든 읽기에서 활성 Catalog revision을 조인한다.
3. VisitReview에 공개 문자열 placeId만 저장하고 Catalog FK를 제거한다.

## 결정 결과

선택한 대안: **Catalog의 stable public-place-id → internal UUID mapping을 write-time에 해석하고, VisitReview에는 UUID FK와 immutable 공개 장소 snapshot을 함께 저장한다**, 그 이유는 DB referential integrity와 과거 후기 재현성을 동시에 지키면서 목록 조회에서 N+1 Catalog 조인을 피하기 때문이다.

Catalog mapping은 public ID별로 하나의 내부 UUID를 가리키며 source revision 교체와 무관하게 유지한다. VisitReview write는 mapping과 현재 공개·후기 작성 가능 Catalog 상태를 확인하고, review row에 publicPlaceId, placeName, regionCode, latitude, longitude를 작성 시점 snapshot으로 보존한다.

## 결과 및 영향

* 장점: 후기·좋아요·신고가 Catalog UUID FK로 참조 무결성을 유지하고, 과거 후기 카드와 지도 핀은 현재 Catalog revision 변화와 독립적으로 재현된다.
* 장점: region/place 목록과 지도 핀 조회는 review snapshot만으로 처리되어 최대 50개 page에서 Catalog N+1 조회가 없다.
* 단점: Catalog ingestion은 public ID mapping을 원자적으로 생성·유지해야 하고 review table에 의도적인 snapshot 중복이 생긴다.
* 단점: 삭제된 Catalog 장소의 신규 후기 작성은 거절하지만 기존 공개 후기는 moderation 정책이 별도로 숨기기 전까지 snapshot으로 남는다.

## 구현 제약

```yaml
constraints:
  - id: visit-review-no-list-n-plus-one
    kind: forbidden_import
    paths: ["modules/community/src/main/java/**"]
    pattern: ["com\.yrootlab\.onmaru\.catalog\.infrastructure"]
    severity: major
    message: "Community list reads must use the stored review snapshot, not catalog infrastructure queries per review."
```

## 확인 방법

PostgreSQL Testcontainers에서 Catalog public ID mapping과 eligible published place를 만들고, VisitReview write가 UUID FK와 snapshot을 함께 저장하는지 검증한다. 장소명·좌표·region이 다음 Catalog revision에서 바뀌거나 장소가 비공개가 된 뒤에도 기존 review 목록이 snapshot을 그대로 반환하는지 검증한다. 좋아요·신고는 review UUID FK를 사용하고, region/place page query가 review와 like aggregate만으로 결과를 만드는 실행 계획을 검토한다.

## 재검토 조건

* 공개 placeId가 provider별로 불안정하거나 다대일 alias 요구가 생겨 stable mapping만으로 수용할 수 없을 때.
* review snapshot의 저장·보존 비용 또는 개인정보·삭제 정책이 history retention을 제한할 때.
* 목록 조회 p99가 합의한 예산을 넘고 실제 실행 계획이 review snapshot index만으로 해결되지 않을 때.
