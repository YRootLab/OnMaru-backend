# Odii Saved Resource Design

## Goal

Issue #113은 회원이 공개 중인 Odii story를 별도 resource type으로 저장하고, `type`이 명시된 개인 목록에서 현재 공개 projection으로 다시 hydrate해 조회하도록 한다.

## Boundaries

- Journey 모듈이 `(memberId, ODII_STORY, storyId)` desired state와 최초 `savedAt`을 소유한다.
- Audio query service가 active revision, story/spot 상태, 안전한 media URL 정책을 재사용해 현재 공개 가능한 story projection만 반환한다.
- 저장된 `ODII_STORY`는 승인된 연결 장소를 hydrate할 수 있지만 `PLACE` 저장 상태를 만들거나 변경하지 않는다.
- `ODII_STORY`는 회원별 300개로 제한하며, limit 도달 전에 저장된 항목의 PUT은 기존 상태를 그대로 반환한다.
- Spring private endpoint는 opaque session과 전역 CSRF 경계를 사용하고 모든 응답을 `no-store`로 처리한다.
- 현재 Audio snapshot 또는 Place detail source를 읽을 수 없으면 저장과 목록은 stale/partial 응답 대신 공통 `SERVICE_UNAVAILABLE` 503으로 fail-closed한다.

## Pagination

최초 저장 시 외부 응답에 노출하지 않는 UUID row ID를 발급하고 중복 PUT에는 같은 row를 유지한다. 목록은 canonical REST 계약에 따라 `savedAt DESC, id DESC`로 정렬한다. cursor는 member ID, resource type, limit, `asOf`, `lastSavedAt`, 내부 `lastId`를 HMAC 서명하고 10분 뒤 만료한다. 다른 member의 cursor는 resource 존재를 숨기기 위해 404, type/limit 변경 또는 변조는 `CURSOR_INVALID`로 처리한다.

목록은 저장 record를 정렬한 뒤 현재 공개 projection을 hydrate한다. 비공개·삭제·모호한 resource는 응답에서 제외하며 raw provider ID는 projection에 포함하지 않는다.

## Persistence

Flyway V006의 `journey_saved_resource_type`은 이미 `PLACE`, `ODII_STORY`를 포함하고 `(member_id, resource_type, resource_id)` 유니크 제약과 목록 인덱스를 제공한다. 이번 in-memory runtime wiring에는 schema나 migration 변경이 필요하지 않다.
