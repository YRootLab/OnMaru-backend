---
id: ADR-0009
title: Grafana Cloud를 MVP 관측성과 alert 상태의 기준으로 사용한다
status: accepted
date: 2026-09-13
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0002
  - ADR-0005
  - ADR-0006
  - ADR-0007
affected_paths:
  - docs/operations/
  - docs/ai/
  - docs/spring/
  - spring/
  - ai/
tags:
  - observability
  - grafana
  - opentelemetry
retrospective: false
---

# Grafana Cloud를 MVP 관측성과 alert 상태의 기준으로 사용한다

## 맥락 및 문제 설명

Spring, FastAPI, 외부 수집, AI run, SSE, moderation은 하나의 요청·run·revision에 걸친 장애 조사가 필요하다. MVP 단계에서 Prometheus, Loki, Tempo, Alertmanager cluster를 직접 운영하면 제품 기능보다 관측 인프라 운영 부담이 커진다. 반대로 Discord만으로는 alert 상태와 조사 이력을 보존할 수 없다.

## 검토한 대안

1. Grafana Cloud로 OTLP metrics/logs/traces를 보내고 dashboard·alert history를 정답으로 둔다.
2. 자체 Prometheus/Loki/Tempo/Alertmanager cluster를 운영한다.
3. 구조화 로그와 Discord notification만 사용한다.

## 결정 결과

선택한 대안: **Grafana Cloud를 observability backend와 alert 상태의 기준으로 사용하고, Spring은 Actuator/Micrometer, FastAPI는 OpenTelemetry SDK로 OTLP를 전송한다**, 그 이유는 MVP의 운영 부담을 낮추면서 두 런타임·수집·AI 실행을 하나의 trace/run/revision 맥락으로 조사하기 때문이다.

## 결과 및 영향

* 장점: dashboard, alert rule, alert history를 중앙화하고 Discord/email은 전달 채널로만 사용한다.
* 단점: 외부 SaaS 의존성, OTLP credential/비용 관리, Grafana 접근 권한과 notification route 운영이 필요하다.

## 확인 방법

Spring-FastAPI trace propagation, requestId/traceId/runId correlation, low-cardinality metric labels, warning/critical test 및 resolve notification, backup/sync/AI/moderation dashboard를 staging에서 검증한다. query, 위치, cookie, token, evidence body는 telemetry에 넣지 않는다.

## 재검토 조건

* 보안·비용·데이터 보존 요구가 managed observability SaaS 사용을 제한한다.
* traffic·retention·custom query 요구가 자체 운영 또는 다른 플랫폼의 총비용을 더 낮춘다.
