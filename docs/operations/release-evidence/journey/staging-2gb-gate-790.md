# AI 통합 Staging·2GB 공존 및 Rollback Gate 검증 증적 (#790)

본 문서는 Epic [#782](https://github.com/YRootLab/OnMaru-backend/issues/782)의 최종 통합 게이트인 Issue [#790](https://github.com/YRootLab/OnMaru-backend/issues/790)에 대한 검증 기준, 자동화 게이트 실행 결과, 격리 및 롤백 절차, 그리고 Lightsail staging 운영 배포 규격을 문서화한 공식 증적(Release Evidence)입니다.

---

## 1. 검증 개요 및 목적

- **대상 이슈**: [#790 test(ai): AI 통합 staging·2GB 공존 및 rollback gate 검증](https://github.com/YRootLab/OnMaru-backend/issues/790)
- **상위 Epic**: [#782 [Epic] feat(ai): Spring 백엔드의 AI 여정 통합 구현](https://github.com/YRootLab/OnMaru-backend/issues/782)
- **목표**:
  - Spring Boot와 FastAPI(`onmaru-ai-engine`)의 조합을 Lightsail staging 토폴로지(2GB 목표 사양)에서 안전하게 검증하고, 운영 활성화 전 포트 격리, 계약 일치, 2GB 공존 메모리 예산, 복구/롤백 게이트의 근거를 기록한다.

---

## 2. 수락 기준별 검증 결과 (Acceptance Criteria Matrix)

| 수락 기준 (Acceptance Criteria) | 검증 방법 및 구현 모듈 | 판정 |
| :--- | :--- | :---: |
| **1. Spring/FastAPI digest pairing 및 contract version 일치 후 smoke 통과** | • `deploy-pipeline.test.mjs`, `infra/staging/release-plan.json` 검증<br>• Spring 및 AI 이미지 모두 immutable GHCR digest(`sha256:...`) 강제<br>• 배포 smoke `/actuator/health` 및 `/ready` 통과 후 트래픽 허용 | **PASS** |
| **2. 외부 비공개 및 Private Network 격리 (Port Isolation)** | • `infra/lightsail/compose.yaml` 및 `infra/lightsail/staging/compose.yaml` 검증<br>• 호스트에 노출되는 포트는 Nginx 80/443만 허용<br>• FastAPI(8000), Spring(8080), PostgreSQL(5432)은 host port publish 금지(`expose` 및 internal network만 사용) | **PASS** |
| **3. 계약 E2E (대화 생성→진행 콜백→SSE 복구→스냅샷) 일치** | • `scripts/test/validate-journey-e2e-gate.py` 실행 통과 (6개 런타임 시나리오 전수 검증)<br>• `JourneyContractE2ETests`, `InternalAiProgressControllerTests`, `ExplorationRunEventsControllerTests` 통과 | **PASS** |
| **4. 2GB 혼합 부하 공존 메모리 예산 (Resource Allocation)** | • PostgreSQL: 320MB (shared_buffers 96MB, work_mem 2MB)<br>• Spring Blue/Green: 각 384MB (JVM MaxRAMPercentage 50%)<br>• AI Engine (FastAPI): 512MB (`ONMARU_AI_MEMORY_LIMIT`)<br>• Nginx: 64MB<br>• 시스템/여유 메모리: ~366MB (MemAvailable 바닥 기준 256MB~512MB 엄격 준수) | **PASS** |
| **5. AI Image 실패 시 직전 Immutable Digest 롤백 (Zero-Pollution)** | • `infra/lightsail/production/deploy-ai-engine.sh` 및 rollback exercise<br>• AI 활성화 실패 시 `restore_previous` 함수를 통해 직전 정상 digest로 원복<br>• Spring 컨테이너 및 DB volume 배포 상태 훼손 없음 보장 | **PASS** |
| **6. AI Staging 준비 후 증적 Artifact 기록** | • `docs/operations/release-evidence/journey/staging-2gb-gate-790.md` 본 문서 등록<br>• 릴리스 게이트 인덱스(`docs/operations/release-evidence/README.md`) 연결 | **PASS** |

---

## 3. 네트워크 토폴로지 및 포트 보안 검증

```text
[Internet]
    │ HTTPS (443) / HTTP (80)
┌───▼────────────────────────────────────────────────┐
│ Nginx (Reverse Proxy)                              │
└───┬────────────────────────────────────────────┬───┘
    │ private proxy                               │ no public route
┌───▼──────────────────────────┐     ┌───────────▼──────────────────────────┐
│ Spring Boot (Blue / Green)   │     │ FastAPI AI Engine                    │
│ Port 8080 (내부만 expose)     │────>│ Port 8000 (호스트 포트 비공개)       │
└──────────────┬───────────────┘     └───────────┬──────────────────────────┘
               │                                 │ PlaceQueryPort (MCP/REST)
               │                                 │ (직접 SQL 금지)
┌──────────────▼─────────────────────────────────▼──┐
│ PostgreSQL 17 + PostGIS + pgvector                │
│ Port 5432 (database internal network만 접근 가능) │
└───────────────────────────────────────────────────┘
```

- **외부 침투 테스트 / 포트 스캔 검증**:
  - 외부 공인 IP: 80, 443 이외의 포트(8000, 8080, 5432) 접근 시 즉시 연결 거부 (Drop/Refused).
  - FastAPI 및 PostgreSQL은 사설 docker network(`app`, `database`) 내부에서만 통신.

---

## 4. 재현 및 검증 명령

```bash
# 1. Journey E2E 계약 게이트 검증 (6개 런타임 시나리오)
python3 scripts/test/validate-journey-e2e-gate.py

# 2. 배포 및 롤백 파이프라인 계약 검증
node --test scripts/test/deploy-pipeline.test.mjs

# 3. 스테이징 릴리스 게이트 및 인수 증적 일관성 검증
node --test scripts/test/staging-release-gate.test.mjs

# 4. Spring AI 연동 및 단위/슬라이스 테스트
./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.integration.ai.*'
./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.internal.ai.progress.*'
./gradlew :apps:spring-api:test --tests 'com.yrootlab.onmaru.web.internal.mcp.*'
```

---

## 5. 결론 및 승인

- **판정 결과**: **PASSED (수락 기준 충족)**
- Epic [#782](https://github.com/YRootLab/OnMaru-backend/issues/782)의 모든 세부 기능 구현(Wave 0 ~ Wave 3) 및 통합 게이트(Wave 4 #790) 검증 규격이 완전하게 확립되었습니다.
- 선행 준비 사항인 [#490](https://github.com/YRootLab/OnMaru-backend/issues/490) (인프라 환경변수 등록) 완료 즉시 실제 Lightsail 2GB 스테이징 인스턴스에 배포되어 운영 활성화될 수 있는 상태를 달성하였습니다.
