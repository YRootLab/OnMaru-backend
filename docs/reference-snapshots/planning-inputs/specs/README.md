# OnMaru Page / Feature / Data Specification System (All-in-One)

OnMaru 프론트엔드(OnMaruFE) 저장소의 실제 구현(Implementation Evidence)을 기반으로 완벽하게 구조화한 **페이지별 기능, 데이터 계약(Data Contract), 백엔드 요구사항(Backend Requirement), 외부 연동 명세 체계**입니다.

> 💡 **백엔드 개발자 / AI 필독 안내 (Handoff Guide)**  
> 백엔드(Spring Boot) 설계 및 구현을 시작하시려면 **[BACKEND_HANDOFF_PROMPT.md](BACKEND_HANDOFF_PROMPT.md)**를 먼저 확인해 주세요!  
> 프론트엔드 코드를 분석할 필요 없이 관광공사 TourAPI 매핑 내역과 API 요구사항을 한눈에 파악하실 수 있습니다.

---

## 🏛️ Specification Hierarchy

```text
Page / Route (pages/)
    ↓
User Capability & Feature Inventory
    ↓
UI Component & Interaction Rules
    ↓
Data Requirement & Data Contract (data-contracts/)
    ↓
Domain Concept & Entity Candidate
    ↓
Backend Requirement (backend-requirements/)
    ↓
DB / API / External Integration Adapter (integrations/)
```

---

## 📂 Complete Specification Inventory

### 1. 종합 분석 보고서 및 백엔드 전달 가이드
- **[ANALYSIS_REPORT.md](ANALYSIS_REPORT.md)**: 레포지토리 전수 분석, 리스크 진단, 불일치(Gap) 및 오픈 퀘스천 종합 보고서
- **[BACKEND_HANDOFF_PROMPT.md](BACKEND_HANDOFF_PROMPT.md)**: 백엔드 개발자/AI에게 전달하는 유연한 설계 가이드 & 복사 프롬프트 💌

### 2. 페이지별 기능 및 뷰 스펙 (`pages/`)
- **[hanok-archive-page.md](pages/hanok-archive-page.md)** (`/hanok`): 전국 한옥 도감 및 계절 큐레이션 **[Reference Spec]**
- **[landing-page.md](pages/landing-page.md)** (`/`): 3D 한옥 9-Beat 시네마틱 스크롤리텔링 & 일영 시뮬레이션
- **[map-page.md](pages/map-page.md)** (`/map`): 카카오맵 정보지도 & 온기지도(혼잡도 히트맵/1줄 온기 피드)
- **[odii-page.md](pages/odii-page.md)** (`/odii`): 오디 오디오 투어, 실시간 자막 동기화, 사운드 성좌도 & AI 도슨트

### 3. 데이터 계약 스펙 (SSOT) (`data-contracts/`)
- **[place-canonical.md](data-contracts/place-canonical.md)** (`DATA-PLACE-CANONICAL`): 도메인 파편화 통합 장소 정규 모델
- **[hanok-village.md](data-contracts/hanok-village.md)** (`DATA-HANOK-001`): 한옥 도감 및 메타데이터 계약
- **[map-warmth-heat.md](data-contracts/map-warmth-heat.md)** (`DATA-MAP-WARMTH-HEAT`): 1줄 온기 후기 & 관광 혼잡도 히트스팟 계약
- **[odii-audio-story.md](data-contracts/odii-audio-story.md)** (`DATA-ODII-AUDIO`): 오디오 이야기 & LRC 초 단위 동기화 자막 계약

### 4. 백엔드 요구사항 및 API 스펙 (`backend-requirements/`)
- **[hanok-archive-backend.md](backend-requirements/hanok-archive-backend.md)** (`BE-REQ-001`~`003`): 한옥 도감/상세 API 및 캐시/장애 격리 전략
- **[map-backend.md](backend-requirements/map-backend.md)** (`BE-REQ-004`~`007`): 반경 장소 검색, 온기 작성/조회 및 혼잡도 API
- **[odii-backend.md](backend-requirements/odii-backend.md)** (`BE-REQ-008`~`009`): 오디오 투어 서빙 및 AI 도슨트 RAG 질의응답 API

### 5. 외부 시스템 연동 스펙 (`integrations/`)
- **[tourapi-adapter.md](integrations/tourapi-adapter.md)**: 한국관광공사 TourAPI 4.0 연동 및 보안/장애 복원력 정책
- **[kakao-map-sdk.md](integrations/kakao-map-sdk.md)**: 카카오맵 Web SDK 라이프사이클, 클러스터링 및 로드뷰 연동
- **[datalab-congestion.md](integrations/datalab-congestion.md)**: 한국관광 데이터랩 외지인 방문객/혼잡도 빅데이터 연동

### 6. 추적 매트릭스 (`traceability/`)
- **[fe-be-traceability-matrix.md](traceability/fe-be-traceability-matrix.md)**: Page → Feature → Component → Data Contract → Domain → Backend Requirement 전체 추적 매트릭스

---

## 🎯 Source of Truth (SSOT) Rules

1. **Implementation First**: 코드와 기존 문서가 상충할 경우 실제 소스코드(`src/`)의 동작을 최우선으로 기록합니다.
2. **Fact / Inference / Proposal 분리**:
   - **Fact**: 코드, 타입, 테스트에서 직접 확인된 사실
   - **Inference**: 구현 증거를 바탕으로 도출한 논리적 추론
   - **Proposal**: 향후 백엔드/데이터베이스 설계를 위한 제안
3. **No Hallucination**: 현재 구현되지 않은 기능은 "Potential Future Requirement"로 엄격히 분리합니다.
