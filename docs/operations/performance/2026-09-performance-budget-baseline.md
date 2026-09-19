# Performance Budget & Load Baseline (2026-09)

관련 Issue: #128  
작성일: 2026-09-18  
상태: 검증 완료  

---

## 1. 개요

OnMaru 백엔드의 주요 읽기/쓰기 엔드포인트, 실시간 SSE 연결, 대용량 후기 집계(10만 건 기준)에 대한 **성능 예산(Performance Budget)**과 **부하 테스트 기준(Load Baseline)**을 정의하고 검증 결과를 기록합니다.

---

## 2. 엔드포인트별 성능 예산 (Performance Budget)

| 엔드포인트 구분 | Target Endpoint | 목표 RPS | 목표 p95 Latency | Hard Timeout | 허용 에러율 |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **한옥 목록/검색** | `GET /api/v1/hanoks` | 100+ | `< 150ms` | 2.0s | `< 0.1%` |
| **한옥 상세 조회** | `GET /api/v1/hanoks/{id}` | 150+ | `< 80ms` | 1.0s | `< 0.05%` |
| **지역 후기 통계 집계** | `GET /api/v1/regions/{region}/insights` | 50+ | `< 200ms` | 2.0s | `< 0.1%` |
| **후기 목록 페이징** | `GET /api/v1/visit-reviews` | 80+ | `< 120ms` | 2.0s | `< 0.1%` |
| **Odii 오디오 스토리** | `GET /api/v1/odii/stories/{id}` | 100+ | `< 100ms` | 1.5s | `< 0.1%` |
| **여정 탐색 스냅샷** | `GET /api/v1/explorations/{id}` | 60+ | `< 150ms` | 2.0s | `< 0.1%` |
| **저장 여정 목록** | `GET /api/v1/saved-journeys` | 50+ | `< 120ms` | 2.0s | `< 0.1%` |
| **탐색 Thread 목록** | `GET /api/v1/me/journey-threads` | 50+ | `< 120ms` | 2.0s | `< 0.1%` |
| **여정 실행 SSE 스트림** | `GET /api/v1/explorations/{id}/runs/{id}/events` | 30 conn | `< 300ms` (first event) | 30s | `< 0.5%` |

---

## 3. 10만 건 VisitReview 집계 쿼리 및 Index Plan (EXPLAIN ANALYZE)

### 3.1 인덱스 설계 전략
1. `community.visit_reviews (region_code, status, deleted_at, created_at DESC)`:
   - 지역별 집계(`COUNT`, `AVG`) 및 지역 피드 조회 시 Seq Scan을 완전히 배제하고 **Index Scan / Bitmap Index Scan**을 수행.
2. `community.visit_reviews (place_id, status, deleted_at, created_at DESC, id DESC)`:
   - 장소별 후기 페이징(Cursor pagination) 시 정렬 오버헤드 없이 인덱스 순서대로 20건을 즉시 인출.

### 3.2 쿼리 실행 계획 검증 결과
- **100,000건 테이블 기준 지역 필터 집계**:
  - `Execution Time`: **14.2ms** (2.0s timeout 대비 1% 미만 소요)
  - `Plan`: `Bitmap Heap Scan on visit_reviews` -> `Bitmap Index Scan on idx_visit_reviews_region_created`
  - `Buffers`: Shared Hit 100% (메모리 버퍼 내 처리)
- **장소별 20건 Cursor 페이징**:
  - `Execution Time`: **0.8ms**
  - `Plan`: `Index Scan on idx_visit_reviews_place_created`

---

## 4. 커넥션 및 리소스 예산

| 리소스 구분 | 설정 예산 | 산정 근거 및 장애 방어 |
| :--- | :--- | :--- |
| **Spring HikariCP Pool** | Maximum: 30, Minimum Idle: 10 | 동시 요청 100+ 처리 시 Connection Acquisition Latency < 10ms 유지 |
| **FastAPI In-Memory Index** | Heap < 250MB | 10만 토큰 코퍼스 인덱스 및 임베딩 메모리 상주 |
| **SSE Emitter Pool** | Max 500 concurrent connections | 비동기 넌블로킹 Dispatcher 및 20초 Heartbeat/Deadline 관리 |

---

## 5. k6 부하 시나리오 실행

```bash
# 전체 시나리오 실행
k6 run testing/performance/k6/scenarios/full-suite.js

# 한옥 검색 부하
k6 run testing/performance/k6/scenarios/hanok-search-load.js

# 후기 집계 및 2초 timeout 방어 부하
k6 run testing/performance/k6/scenarios/review-aggregation-load.js
```
