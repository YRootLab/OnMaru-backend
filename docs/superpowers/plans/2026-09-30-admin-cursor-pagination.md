# 관리자 목록 서버 페이지네이션 구현 계획 (#509)

## 현재 동작과 확인된 비용

- `AdminReviewController` 후기 조회는 `VisitReviewStore.findSnapshot()` 전체 결과를 Java에서 status 필터·정렬·limit 처리한다. JDBC snapshot은 후기 본문과 좋아요를 모두 읽는다.
- 신고 조회는 `ReviewReportStore.openReports()` 전체 결과를 읽어 Java에서 정렬·limit 처리한다.
- `ModerationQueueService`는 모든 후기, 전체 moderation audit, 전체 open report를 한 번에 읽어 SLA queue를 계산한다.
- `AdminUserController`는 limit을 SQL에 전달하지만 cursor와 `hasNext`가 없고, JDBC query는 review join/group by 후 `created_at DESC`만 정렬한다.
- `AdminCurationController`도 SQL limit은 있지만 cursor가 없으며 latest override를 `DISTINCT ON`으로 구한 다음 filter/order/limit한다.
- `AdminDashboardService`는 전체 후기를 읽어 기간별 통계와 최근 5개를 Java에서 계산한다.

## 계약

- 기존 endpoint와 응답 필드는 유지하고 `cursor` query 및 `nextCursor` 응답 필드를 추가한다. legacy 응답의 `hasNext`도 유지한다.
- 기본 limit은 20, 최대 limit은 100이다. 잘못된 limit/cursor는 400 `VALIDATION_ERROR`다.
- 정렬은 각 리소스의 최신 timestamp 내림차순, UUID 내림차순으로 고정한다. 다음 페이지 predicate는 `(created_at, id) < (:createdAt, :id)` 형태다.
- cursor payload에는 version, resource, limit, filter fingerprint, 마지막 timestamp와 UUID를 넣고 HMAC-SHA256으로 서명한다. 다른 endpoint·limit·filter에서 재사용한 cursor 및 변조 cursor는 거부한다.
- `limit + 1` row를 조회해 `hasNext`와 next cursor를 계산한다. offset은 사용하지 않는다.

## 구현 경계

1. 공용 `AdminPage<T>`와 서명 cursor codec을 추가한다. cursor key는 설정 secret에서 주입하고 production에서는 누락/약한 기본값을 허용하지 않는다.
2. 회원/큐레이션 store부터 page query로 전환한다. 회원 review count는 별도 correlated aggregate 또는 사전 집계 전략을 비교하고, 페이지 후보 회원을 먼저 keyset으로 한정한 뒤 count를 계산한다.
3. 후기 조회용 전용 admin projection query를 추가한다. 전체 화면에 필요한 writer fields만 선택하고 좋아요 배열은 join하지 않는다.
4. 신고 목록은 `(created_at, id)` keyset으로 SQL 조회한다.
5. moderation queue는 미처리 신고/최신 moderation action을 SQL에서 후보 limit+1로 가져오고, review와 필요한 report/action만 join하여 기존 SLA/PII 우선순위를 유지한다. 후보를 임의 limit으로 자르기 전에 우선순위 순서가 DB에서 완전히 보장되는지 검증한다.
6. dashboard는 date/status별 `COUNT(*) FILTER` aggregate와 `ORDER BY created_at DESC, id DESC LIMIT 5` 두 쿼리로 나눈다.
7. `docs/contracts/openapi/admin.openapi.yaml` 및 FE integration 문서를 갱신한다.

## 인덱스와 성능 검증

- 먼저 현재 migration에서 `community_visit_reviews(status, created_at, id)`, `community_review_reports(status, created_at, id)`, `identity_members(status, created_at, id)`, curation latest-row key 인덱스가 있는지 확인한다.
- 운영 유사 데이터 baseline에서 각 filter/order query에 `EXPLAIN (ANALYZE, BUFFERS, FORMAT TEXT)`를 저장한다. 결과 없이 인덱스를 추가하지 않는다.
- index가 필요하면 PostgreSQL migration 제약을 확인한다. 저장소의 V033 migration은 Flyway schema-history와 DDL 원자성을 위해 `CREATE INDEX CONCURRENTLY`를 의도적으로 쓰지 않는다. 따라서 실제 운영 적용 방식은 migration runner/lock 및 배포 downtime 기준을 함께 검토하고, 근거 없이 `CONCURRENTLY`를 넣지 않는다. baseline/candidate SQL과 latency·buffer·returned rows를 report에 기록한다.
- Testcontainers에 같은 timestamp의 여러 UUID, status/filter 조합, 크기 101+ fixture를 두고 중복/누락 없이 다음 페이지를 검증한다.
- snapshot 호출이 pagination 경로에서 호출되지 않는 것을 adapter test로 확인하고, 전체 admin API test 및 OpenAPI contract 검증을 실행한다.

## 남은 사전 결정

- production cursor HMAC key를 어느 secret property로 받을지 확인한다. 임시 고정 signing key를 코드에 넣지 않는다.
- 회원 검색 field와 정렬 옵션은 현재 Issue의 요구 범위에 없으므로 status filter와 고정 최신순부터 구현한다.
