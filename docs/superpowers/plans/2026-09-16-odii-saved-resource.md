# Odii Saved Resource Implementation Plan

**Goal:** 회원 전용 Odii 저장/삭제와 type별 current-public saved-resource 목록을 구현한다.

**Architecture:** Journey가 저장 desired state와 정렬 record를 소유하고, Spring 목록 adapter가 Catalog/Audio의 현재 공개 query projection으로 hydrate한다. cursor는 공통 HMAC codec으로 actor와 query shape에 묶는다.

## Tasks

- [x] MockMvc RED 테스트로 저장/삭제 멱등성, auth/CSRF, hidden story, type-required 목록, actor-bound cursor를 고정한다.
- [x] Journey에 `ODII_STORY` desired-state store/service와 type별 record source를 구현한다.
- [x] Audio 공개 query 정책을 재사용하는 saved-story projection을 추가한다.
- [x] member-only controller, current hydration, signed stable cursor와 private no-store 경계를 연결한다.
- [x] provider raw ID 비노출과 PLACE 비자동 저장을 회귀 테스트한다.
- [x] focused/full verification과 저장소 hygiene 검증을 수행한다.
- [ ] branch를 push하고 `develop` 대상 PR을 생성해 CI와 독립 review를 확인한다.
