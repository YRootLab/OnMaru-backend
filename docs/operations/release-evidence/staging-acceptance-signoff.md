# OnMaru Backend Staging Release Gate & 운영 인수 서명서 (Sign-Off)

본 문서는 OnMaru 백엔드 시스템의 Phase 1~6 전체 구현 및 운영 준비 상태를 종합 평가하고, Staging 배포 및 프로덕션 출시 가능 여부를 판정하는 공식 인수 서명서입니다.

---

## 1. 릴리스 게이트 종합 판정 결과

- **판정 일자**: 2026-09-18
- **대상 브랜치**: `develop` (Release Candidate)
- **최종 판정**: **PROCEED TO PRODUCTION (출시 승인)**
- **평가 게이트 통과율**: **9 / 9 (100% PASSED)**

---

## 2. 게이트별 평가 및 증적 매트릭스

| Gate ID | 영역 | 평가 항목 | 검증 결과 | 증적 문서 / 스크립트 |
| :--- | :--- | :--- | :---: | :--- |
| **GATE-01** | R1 카탈로그 | TourAPI 파싱, 키 마스킹, LKG 리비전 | **PASS** | `docs/operations/release-evidence/r1/README.md` |
| **GATE-02** | R2 미디어/저장 | Odii 오디오/대본, R2 스토리지, 후기 격리 | **PASS** | `docs/operations/release-evidence/r2/README.md` |
| **GATE-03** | R3 여정/AI | Journey OpenAPI 3.1, SSE 생명주기, AI 연동 | **PASS** | `docs/operations/release-evidence/journey/README.md` |
| **GATE-04** | 보안/인증 | 세션 경계, PII 마스킹, 시크릿 수명주기 | **PASS** | `docs/operations/runbooks/secrets.md` |
| **GATE-05** | DB/복구 | Flyway 마이그레이션, PostgreSQL PITR 드릴 | **PASS** | `docs/operations/runbooks/restore.md` |
| **GATE-06** | 성능 예산 | 한옥 검색 p99 < 150ms, SSE 지연 < 200ms | **PASS** | `docs/operations/performance/2026-09-performance-budget-baseline.md` |
| **GATE-07** | 장애 회복 | TourAPI/AI 다운 시 Fallback, Stale Write 0건 | **PASS** | `docs/operations/runbooks/degraded-mode.md` |
| **GATE-08** | 관측성/Alert | OpenTelemetry 트레이싱, Grafana 대시보드 | **PASS** | `docs/operations/release-evidence/grafana-o02-checklist.md` |
| **GATE-09** | 배포/롤백 | 멀티스테이지 컨테이너, 배포 순서 및 롤백 | **PASS** | `docs/operations/runtime-and-reliability.md` |

---

## 3. 핵심 운영 지표 및 SLA 준수 증명

1. **가용성 및 무중단 서빙**:
   - 외부 의존성(TourAPI, Odii, Gemini API) 장애 시에도 LKG 데이터셋 및 Deterministic Baseline 추천을 통해 100% 정상 가용성 유지.
2. **동시성 및 데이터 무결성**:
   - CAS(Compare-And-Swap) 및 비동기 콜백 검증을 통해 취소/타임아웃 여정에 대한 Stale Write 0건 보장.
3. **재해 복구 역량 (Disaster Recovery)**:
   - 2-DB 격리 복구 리허설 결과 RTO 15분 미만(목표 < 30분), RPO 0초(목표 < 5분) 달성.
4. **성능 예산 준수**:
   - k6 부하 시나리오 및 EXPLAIN 인덱스 쿼리 분석 결과 모든 p99 지연 시간 및 2초 타임아웃 예산 충족.

---

## 4. 운영 인수 서명 (Sign-Off)

- **Backend Architecture Lead**: 승인 (Approved)
- **Reliability & DevOps Lead**: 승인 (Approved)
- **AI & Data Engineering Lead**: 승인 (Approved)
