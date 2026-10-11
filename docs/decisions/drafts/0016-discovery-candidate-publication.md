---
id: ADR-0016
title: 전통문화 신규 탐색의 후보 판정과 별도 게시본을 도입한다
status: proposed
date: 2026-10-08
locale: ko
related:
  - ADR-0006
  - ADR-0010
affected_paths:
  - modules/catalog/
  - apps/spring-api/
  - docs/contracts/discovery-kcontents-api.md
tags:
  - catalog
  - discovery
  - k-content
---

# 전통문화 신규 탐색의 후보 판정과 별도 게시본을 도입한다

## 관측 사실

#681의 2026-10-08 실측에서 선택 34개 소분류 9,801건과 검색 구제 57건을 확인했다. 기존 운영 원천은 49,613건, 공개 장소는 23,637건이다. 새 후보와 기존 공개의 교집합은 7,475건이다. 자세한 호출·차집합·표본과 해시 한계는 [정책 검증](../../planning/place-kcontents/policy-validation.md)에 기록했다.

## 제안 결정

1. `discovery-candidate-v1.0.0`을 후보 정책 버전으로 고정한다. 원천 TourAPI 분류는 보존하고 `INCLUDE/REVIEW/EXCLUDE`, reason code, 역할, 사람 override를 별도로 저장한다. `INCLUDE`는 게시가 아닌 후보이며 세부 근거·좌표·권리 gate가 따로 있다.
2. 새 탐색은 기존 `kto-korean-tour` active pointer와 분리한 revision을 게시한다. 원천 일부 실패·빈 결과·판정 실패에서는 마지막 정상 신규 revision과 기존 공개 revision을 유지한다. 기존 장소 identity 및 사용자 참조는 삭제하지 않는다.
3. 궁궐·성곽·고택 같은 전통 공간은 탐색의 핵심이지만 `HANOK`으로 허위 저장하지 않는다. 넓은 카페·체험마을·검색 구제는 세부 근거 전 자동 공개하지 않는다.
4. 작품–장소 관계는 신규 데이터/조회 경계에서 출처 URL의 존재만으로 게시하지 않고 해당 장소에서 해당 작품이 실제 촬영됐다는 근거를 검증한다. 권리 불명 이미지는 게시하지 않는다.

## ADR-0010과 관계

ADR-0010은 기존 screen-hanok 기능의 자동 게시 예외를 승인했다. 이 제안은 그 기존 API와 관계를 변경하지 않고 **신규 탐색과 신규 K-Contents API**에 더 엄격한 판정·검수 경계를 둔다. 별도 승인 없이 ADR-0010을 superseded로 표기하거나 운영 게시 정책을 소급 변경하지 않는다.

## 검토한 대안

- 기존 공개 23,637건의 active pointer를 새 후보로 즉시 교체: 심사 중 홈·지도·한옥·찜·상세 계약과 사용자 참조가 바뀌어 기각.
- 분류 코드만으로 일괄 공개: 카페·사찰·시장·체험마을의 오분류가 실제 표본에서 확인돼 기각.
- 모든 후보를 영구 REVIEW: 새 탐색의 단계적 검증과 100→1,000 pilot에 쓸 고신뢰 후보가 없어 기각.

## 승인 전 검증 신호

W1/W2의 동일 capture 재생, W3의 legacy active 불변과 마지막 정상본 보존, W4/W7의 근거 검증, W8/W9의 신규 읽기 계약, W10의 스테이징 롤백 및 기존 API golden fixture가 필요하다. 신규 탐색 공개는 별도 배포 승인 대상이다.
