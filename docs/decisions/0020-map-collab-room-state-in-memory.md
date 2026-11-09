---
id: ADR-0020
title: 지도 공동 편집 방 상태는 Spring 메모리에서 단일 소유자로 처리하고 핀은 PostgreSQL에 write-through 한다
status: proposed
date: 2026-11-09
locale: ko
decision_makers:
  - 사용자
related:
  - ADR-0003
  - ADR-0004
affected_paths:
  - docs/contracts/map-collab-api.md
  - docs/planning/map-collab/
  - apps/spring-api/src/main/resources/db/migration/baseline/V058__map_collab_schema.sql
tags:
  - map-collab
  - websocket
  - realtime
retrospective: false
---

# 지도 공동 편집 방 상태는 Spring 메모리에서 단일 소유자로 처리하고 핀은 PostgreSQL에 write-through 한다

## 맥락 및 문제 설명

지도 화면을 여러 사용자가 같은 방에서 함께 본다. 핀 추가·삭제, 선의 순서, 커서, 참여자 목록이 실시간으로 맞아야 한다. 핀 순서는 선의 모양을 결정하므로 동시 편집에서도 순서가 깨지면 안 된다.

현재 상황은 다음과 같다.
- 백엔드에는 Redis가 없다. PostgreSQL은 이미 운영 중이다.
- 배포는 Lightsail 단일 호스트의 Docker Compose + nginx blue/green이다. 배포 전환 중에는 두 컨테이너가 잠시 공존한다.
- FE는 Liveblocks로 구현되어 있으며, 백엔드가 준비되면 WebSocket으로 전환하기로 했다.
- 핀은 사람 속도로 바뀌므로(분당 몇 건) DB 부하는 작다. 커서처럼 고빈도인 값은 저장할 필요가 없다.

## 검토한 대안

1. **Spring 메모리 + 방별 단일 소유자 + 핀 write-through (선택)**
   - 방 연산을 한 큐에서 처리하면 락 경합이 없고 순서 충돌이 없다.
   - 지연이 가장 짧고 인프라가 늘지 않는다.
   - 단점: 인스턴스가 여럿이면 방 상태가 인스턴스마다 나뉜다. 재배포 시 연결이 끊긴다. 다만 핀은 DB에 즉시 저장되므로 재접속 시 스냅샷으로 복구된다.
2. **Redis를 상태 저장소 + Pub/Sub 팬아웃으로 사용**
   - 여러 인스턴스가 상태를 공유한다.
   - 단점: Pub/Sub은 최대 한 번 전달이라 유실이 가능하다. 순서 보장에 원자 연산이 필요하다. 정답 데이터가 Redis와 DB에 나뉜다. 현재 Redis 운영 경험과 인프라가 없다.
3. **Liveblocks 유지**
   - FE 구현은 이미 있다.
   - 단점: 방 상태가 외부 SaaS에 있다. 백엔드가 방 상태를 소유하지 못해 DB 기반 이력·감사·코스 저장과 연결이 어렵다. 사용량 과금과 벤더 종속이 생긴다.
4. **Spring WebSocket + Yjs(CRDT)**
   - 충돌 해결을 라이브러리에 맡긴다.
   - 단점: 서버가 정책(상한, 권한, 감사)을 강제하기 어렵다. 서버 측 검증과 이벤트 감사가 어렵다. FE와 BE 모두 복잡도가 크다.

## 결정 결과

선택한 대안: **대안 1**, 그 이유는 현재 규모·인프라·요구(순서 보장, 감사, DB 영속)에 가장 단순하게 맞고, 나중에 다중 인스턴스로 갈 때 방 단위 라우팅(nginx URI 해시)으로 Redis 없이 확장할 수 있기 때문이다.

결정을 구성하는 원칙
1. 방 상태(활성 핀, 멤버 연결)는 메모리에 두고, 방마다 단일 처리 큐를 둔다.
2. 핀 변경은 DB 트랜잭션(이벤트 행 + 핀 행 + 방의 `last_event_seq`) 커밋 후에 메모리에 반영하고 브로드캐스트한다.
3. 방 내 순서의 단일 권위는 `map_room_events.seq`다. 핀의 선 순서는 `added_seq`(= 이벤트 seq)다.
4. 커서·presence·연결 객체는 저장하지 않는다.
5. 연결 상태 저장소와 이벤트 발행을 인터페이스(`RoomRegistry`, `RoomEventPublisher`)로 분리해 Redis·Kafka 전환 시 서비스 코드를 바꾸지 않는다.
6. 모듈은 `modules/map-collab`에 두고 ADR-0003의 순수 core + consumer-owned port 규칙을 따른다. WS 어댑터와 JPA 어댑터는 바깥 계층에 둔다.

## 결과 및 영향

* 장점: 구현이 단순하고 순서 보장이 명확하다. DB 이력으로 감사·코스 저장이 가능하다. 외부 SaaS 의존이 없다.
* 단점: 단일 인스턴스 전제다. 배포 중에는 연결이 끊기고 재접속 복구가 필요하다. 방 상태 메모리 상한을 설정으로 강제해야 한다.
* FE 영향: Liveblocks 패키지, `/api/liveblocks-auth` 라우트, 관련 환경 변수를 제거하고 WS 클라이언트를 구현해야 한다. 초대 방식도 `?room=UUID`에서 `?invite=TOKEN`으로 바뀐다.

## 확인 방법

* V058 DDL을 PostgreSQL 16에 적용하고 제약을 검증한다. 같은 장소 활성 핀 중복 거부, 좌표 범위, `client_op_id` 멱등, 제거 후 재핀 허용을 확인한다. (2026-11-09 로컬 검증 완료)
* 명령 처리 단위 테스트: 같은 `opId` 재전송 시 같은 결과, DB 실패 시 메모리 불변, 상한 초과 거부.
* 재시작 복구 테스트: 활성 핀과 `last_event_seq`가 DB에서 재구성되고, 새 이벤트 seq가 되돌아가지 않는다.
* 재연결 테스트: `lastSeq` 재생, 보관 범위 밖이면 `RESYNC_REQUIRED`.
* 부하 확인: 방 20명 × 커서 20Hz에서 CPU와 발신 큐 상한이 설정 범위 안에 있는지 확인한다.

## 재검토 조건

* 애플리케이션 인스턴스를 2대 이상 상시 운영해야 할 때 (nginx URI 해시 라우팅으로 먼저 검토)
* 방 간 공유 상태나 전역 팬아웃이 필요해질 때 (그때 Redis Streams 검토)
* 방당 이벤트량이 DB 쓰기 한계에 가까워질 때 (커서가 아니라 핀 기준으로 본다)
* 감사 요구로 이벤트를 외부 로그 시스템으로 발행해야 할 때 (Debezium CDC 또는 outbox 발행자 추가)
