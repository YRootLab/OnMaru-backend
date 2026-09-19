---
id: ADR-0006
title: fenced dataset revision으로 마지막 정상 데이터를 원자적으로 게시한다
status: accepted
date: 2026-09-13
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0002
affected_paths:
  - docs/spring/
  - docs/operations/
  - docs/database/
  - spring/
tags:
  - ingestion
  - dataset-revision
  - reliability
retrospective: false
---

# fenced dataset revision으로 마지막 정상 데이터를 원자적으로 게시한다

## 맥락 및 문제 설명

외부 관광 API 수집은 pagination 실패, HTTP 200 오류 envelope, 중복·형식 오류, lease 만료 worker, 원천 삭제를 처리해야 한다. row 단위 upsert를 즉시 공개하면 부분 수집이나 늦은 worker가 사용자의 공개 catalog를 훼손할 수 있다.

## 검토한 대안

1. private staging revision을 검증한 뒤 active pointer를 fenced CAS로 전환한다.
2. row별 마지막 정상 값(LKG)을 즉시 갱신하여 mixed revision을 허용한다.
3. staging table 전체를 DDL table swap으로 교체한다.

## 결정 결과

선택한 대안: **private dataset revision, DB-clock lease generation fence, active pointer CAS를 사용한 원자 게시**, 그 이유는 완결된 데이터 집합만 공개하고 partial failure와 stale worker의 쓰기를 차단하기 때문이다.

## 결과 및 영향

* 장점: 마지막 정상 공개 revision을 유지하며, tombstone과 source watermark를 검증된 publish transaction에 묶을 수 있다.
* 단점: revision 저장 공간, GC, dataset 사이 mixed revision 노출 정책을 운영해야 한다.

## 확인 방법

provider별 마지막 page 실패, 429/timeout, quarantine, A worker lease 만료 뒤 B publish와 A late write, tombstone, revision GC를 integration test와 redacted live capture로 검증한다. `LIVE_CANONICAL`은 첫 adapter의 실제 contract 검증 전에는 활성화하지 않는다.

## 재검토 조건

* revision 저장량 또는 pointer publish transaction 시간이 합의한 예산을 넘는다.
* 공급자가 강한 snapshot/cursor와 더 단순한 무손실 증분 계약을 제공해 동일 안전성을 낮은 비용으로 달성한다.
