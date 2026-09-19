# OnMaru Degraded Mode & Chaos Resilience Runbook

본 문서는 외부 공급자(TourAPI, Odii), 내부 AI 엔진(FastAPI), 실시간 SSE 스트리밍, 비동기 런 상태 머신에서 장애가 발생했을 때 시스템이 제공하는 **Degraded Mode(기능 축소 무중단 운영)** 동작 규약과 운영자 대응 절차를 정의합니다.

---

## 1. 아키텍처 회복 탄력성 원칙 (Resilience Principles)

1. **Graceful Degradation (우아한 기능 축소)**:
   - 외부 API가 다운되거나 Rate Limit(429)에 도달해도 사용자의 핵심 조회 기능은 중단되지 않습니다.
   - AI 마이크로서비스 장애 시 정적 룰 기반의 Deterministic Baseline 알고리즘으로 즉각 fallback 하여 사용자 여정 생성을 보장합니다.
2. **Zero Stale Writes (상태 오염 0건 보장)**:
   - 타임아웃, 사용자 취소 또는 네트워크 지연으로 인해 늦게 도착한 비동기 콜백(Late Callback)이나 중복 요청은 데이터베이스 락과 상태 머신 불변식을 통해 원천 차단됩니다.
3. **Fault Isolation (장애 격리)**:
   - 공급자 동기화 실패나 파싱 오류는 격리 큐(Quarantine)에 적재되며, 현재 서비스 중인 활성 데이터셋 리비전(LKG Dataset Revision)을 오염시키지 않습니다.

---

## 2. Degraded Mode 동작 매트릭스

| 장애 대상 | 결함 유형 | 시스템 동작 (Degraded Mode) | 사용자 영향 | 자동 복구 방식 |
| :--- | :--- | :--- | :--- | :--- |
| **TourAPI** | 429 Too Many Requests / 5xx 다운 | 직전 유효 검증된 **LKG Dataset Revision**에서 즉시 조회 서빙 | 조회 무중단 (실시간 최신 반영만 일시 지연) | 서킷 브레이커 쿨다운(5분) 후 점진적 프로브 |
| **Odii** | R2 스토리지 장애 / API 타임아웃 | 스토리 메타데이터 및 **대본 텍스트 정상 서빙**, 오디오 스트림만 대체 안내 | 텍스트 대본 열람 가능, 오디오 재생 일시 비활성화 | CDN 캐시 히트 우선 서빙 및 재시도 |
| **FastAPI** | 프로세스 크래시 / 응답 지연 (>5s) | Spring Worker가 **Deterministic Baseline Ranker**로 즉시 전환 | 규칙 기반의 최적 한옥 여정 100% 정상 제공 | 백그라운드 헬스체크 성공 시 AI 모델 자동 재연결 |
| **SSE Stream**| 네트워크 단절 / 클라이언트 재접속 | **Last-Event-ID** 기반 Replay Buffer (60초) 재생 또는 최신 Snapshot Reset 발송 | 새로고침 없이 상태 실시간 동기화 복구 | 클라이언트 자동 재연결 핸드셰이크 |
| **Async Run** | 타임아웃/취소 후 지연 도착 콜백 | 상태 머신 전이 검증으로 **409 Conflict 반환 및 DB 갱신 거부 (Stale Write = 0)** | 이전 작업과의 상태 충돌 없음 | 비동기 멱등성 키 및 버전 검증 |

---

## 3. 세부 컴포넌트별 런북 및 복구 절차

### 3.1 TourAPI / 공공데이터 장애
- **증상**: `tourapi_client_error_rate_5xx` > 5% 또는 `tourapi_rate_limit_hits_total` 증가
- **시스템 상태**:
  - `DatasetRevisionService`는 활성 LKG(Last-Known-Good) 리비전을 고정 유지합니다.
  - 배치 동기화 워커는 지수 백오프(Exponential Backoff with Jitter)로 격리됩니다.
- **운영자 조치**:
  1. 공공데이터포털 API 승인 키 및 일일 호출 잔여량 확인.
  2. 일일 쿼터 초과 시 공공데이터포털 관리자에게 일시 쿼터 상향 요청.
  3. 서비스 복구 후 동기화 수동 트리거:
     ```bash
     curl -X POST http://localhost:8080/api/v1/internal/sync/tourapi/trigger -H "X-Internal-Token: ${INTERNAL_API_SECRET}"
     ```

### 3.2 Odii 오디오 / 대본 장애
- **증상**: `odii_provider_request_errors_total` 급증
- **시스템 상태**:
  - 오디오 요청 엔드포인트는 `200 OK`와 함께 `audio_available: false`, `degraded_notice: "AUDIO_TEMPORARILY_UNAVAILABLE"` 응답 반환.
  - 대본 텍스트 및 위치 정보는 로컬 카탈로그 DB에서 정상 반환.
- **운영자 조치**:
  1. Cloudflare R2 버킷 헬스체크 및 CDN 전송 상태 확인.
  2. 오디오 파일 누락 건의 경우 재동기화 배치 실행.

### 3.3 FastAPI AI 엔진 장애 & Baseline Fallback
- **증상**: `fastapi_circuit_breaker_state == OPEN` 또는 `journey_baseline_fallback_triggered_total` 급증
- **시스템 상태**:
  - Spring `JourneyWorker`는 FastAPI 호출 실패 즉시 `DeterministicBaselineRanker`를 호출하여 평점, 테마 적합도, 거리 기반 최적 한옥 여정을 조합하여 완료 처리.
  - `RunSnapshot`에 `fallback: true`, `source: "DETERMINISTIC_BASELINE"` 메타데이터 기록.
- **운영자 조치**:
  1. FastAPI Pod 로그 및 GPU/CPU 리소스 상태 점검:
     ```bash
     kubectl logs -n onmaru -l app=fastapi-ai --tail=100
     ```
  2. LLM Provider(Gemini API 등) Quota 및 에러율 점검.

### 3.4 SSE 연결 복구 및 Replay 버퍼
- **증상**: `sse_reconnect_replay_events_total` 증가
- **시스템 상태**:
  - 클라이언트가 `Last-Event-ID` 헤더를 포함하여 재접속하면 메모리 버퍼(최대 60초 보관)에서 누락된 이벤트만 순차 전송.
  - 60초 초과 단절인 경우 `SNAPSHOT_RESET` 이벤트를 최초 전송하여 최신 전체 상태 동기화.
- **운영자 조치**: 일반적인 모바일 네트워크 전환(WiFi -> LTE) 시 정상 작동하므로 별도 수동 개입 불필요.

### 3.5 비동기 콜백 Stale Write 방어
- **증상**: `journey_stale_callback_rejected_total` 증가
- **시스템 상태**:
  - 이미 `CANCELLED`, `TIMED_OUT`, `COMPLETED` 등 터미널 상태에 진입한 여정 Run에 대해 AI 서버에서 지연 응답이 오더라도 낙관적 락(`version`) 및 상태 불변식 검증에 의해 409 Conflict 처리되며 데이터베이스 갱신이 원천 차단됩니다.
- **운영자 조치**: 정상적인 방어 메커니즘 동작이므로 로그 레벨 `WARN` 수준에서 모니터링.

---

## 4. 모니터링 알림 임계치 (Alerting Thresholds)

| 메트릭 이름 | Warning 임계치 | Critical 임계치 | 알림 채널 |
| :--- | :--- | :--- | :--- |
| `fastapi_circuit_breaker_state` | OPEN (1분 지속) | OPEN (5분 지속) | Slack `#ops-backend-alerts` |
| `journey_baseline_fallback_triggered_total` | > 10 req/min | > 50 req/min | Slack + PagerDuty |
| `ai_proposal_generation_failures_total` | > 5 req/min | > 20 req/min | Slack `#ops-backend-alerts` |
| `tourapi_client_error_rate_5xx` | > 5% (10분) | > 20% (10분) | Slack `#ops-backend-alerts` |
| `tourapi_rate_limit_hits_total` | > 10 req/min | > 50 req/min | Slack `#ops-backend-alerts` |
| `dataset_revision_lkg_serving_active` | LKG active > 24h | LKG active > 72h | Slack `#ops-backend-alerts` |
| `odii_provider_request_errors_total` | > 5 req/min | > 20 req/min | Slack `#ops-backend-alerts` |
| `odii_audio_cache_miss_on_outage_total` | > 10 req/min | > 50 req/min | Slack `#ops-backend-alerts` |
| `sse_active_connections` | > 5000 conns | > 10000 conns | Slack `#ops-backend-alerts` |
| `sse_reconnect_replay_events_total` | > 50 req/min | > 200 req/min | Slack `#ops-backend-alerts` |
| `sse_snapshot_reset_events_total` | > 10 req/min | > 50 req/min | Slack `#ops-backend-alerts` |
| `journey_stale_callback_rejected_total` | > 10 req/min | > 100 req/min | Slack `#ops-backend-alerts` |
| `journey_run_state_conflict_total` | > 5 req/min | > 30 req/min | Slack `#ops-backend-alerts` |

---

## 5. 검증 및 리허설 (Verification)

카오스 리허설 매니페스트 및 자동화 테스트는 다음 명령어로 즉시 검증 가능합니다:
```bash
node --test scripts/test/degraded-mode.test.mjs
```
