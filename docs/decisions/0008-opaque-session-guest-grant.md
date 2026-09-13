---
id: ADR-0008
title: Kakao 로그인은 opaque server session과 제한된 guest grant로 연결한다
status: proposed
date: 2026-09-13
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0002
  - ADR-0004
affected_paths:
  - docs/spring/
  - docs/contracts/
  - docs/database/
  - spring/
tags:
  - authentication
  - session
  - ownership
retrospective: false
---

# Kakao 로그인은 opaque server session과 제한된 guest grant로 연결한다

## 맥락 및 문제 설명

비회원은 여정을 먼저 탐색하고 로그인 후 저장할 수 있어야 한다. 그러나 Kakao access token, provider subject, 탐색 ID를 browser가 소유권 증명으로 사용하면 탈취·재사용·다른 탐색 접근 위험이 생긴다. MVP의 활성 OAuth provider는 Kakao 하나지만 내부 회원 ID는 provider에 종속되어서는 안 된다.

## 검토한 대안

1. server-side opaque session과 짧은 guest-to-member exploration grant를 사용한다.
2. browser가 Kakao access token을 보관하고 매 요청 전달한다.
3. guest exploration을 로그인 callback에서 회원 소유로 직접 전환한다.

## 결정 결과

선택한 대안: **Kakao OAuth 뒤 OnMaru opaque server session을 발급하고, 검증된 기존 guest exploration 하나에만 10분 guest grant를 발급한다**, 그 이유는 provider credential을 browser의 장기 권한으로 사용하지 않으면서 로그인 전 탐색 흐름을 보존하기 때문이다.

## 결과 및 영향

* 장점: provider subject와 OAuth token을 public DTO·localStorage에서 분리하고, grant의 scope·만료·단회 사용을 서버에서 검증할 수 있다.
* 단점: CSRF, state, cookie rotation, guest cleanup, callback·탈퇴 경합을 구현하고 테스트해야 한다.

## 확인 방법

OAuth state 최초 성공·재사용·만료·browser nonce 불일치, 허용되지 않은 return path, guest grant 만료, CSRF 누락·rotation, 탈퇴 중 늦은 AI 결과를 fixture와 integration test로 검증한다. browser 응답·로그·fixture에는 OAuth token, refresh token, client secret을 저장하지 않는다.

## 재검토 조건

* native mobile 또는 cross-origin client가 cookie/session 방식을 충족하지 못한다.
* 다수 OAuth provider의 연결·계정 병합 또는 기업 SSO가 별도 identity provider를 요구한다.
