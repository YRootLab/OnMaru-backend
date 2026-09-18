# OnMaru FE-BE Traceability Matrix

> **문서 목적**: 프론트엔드 라우트 화면부터 사용자 기능, UI 컴포넌트, 데이터 계약, 도메인 개념, 백엔드 요구사항 및 소스코드 근거(Evidence)까지 End-to-End 연결 관계를 추적하는 매트릭스입니다.

---

## Traceability Table

| Page | Feature ID | UI Component | Data Contract | Domain Concept | Backend Requirement Candidate | Code Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`/`** | `LAND-F001` | `LandingExperience` | `DATA-LAND-001` | 3D Scene / Camera | - (Client Only) | `src/LandingExperience.jsx` |
| **`/`** | `LAND-F002` | `LandingSolarShadow` | `DATA-LAND-001` | Solar Calculation | - (Client Only) | `src/components/landing/LandingSolarShadow.jsx` |
| **`/`** | `LAND-F003` | `LandingHanokAssembly` | `DATA-LAND-001` | 3D Mesh Assembly | - (Client Only) | `src/components/landing/LandingHanokAssembly.jsx` |
| **`/hanok`** | `HANOK-F001` | `HanokGrid` | `DATA-HANOK-001` | Hanok Village | `BE-REQ-001` (`GET /api/v1/hanoks`) | `src/hanok/sections/HanokGrid.tsx` |
| **`/hanok`** | `HANOK-F002` | `HanokMonthly` | `DATA-HANOK-001` | Curation Story | `BE-REQ-003` (`GET /api/v1/hanoks/curation/monthly`) | `src/hanok/sections/HanokMonthly.tsx` |
| **`/hanok`** | `HANOK-F003` | `HanokMap` | `DATA-HANOK-001` | Map Location | `BE-REQ-001` (`GET /api/v1/hanoks`) | `src/hanok/sections/HanokMap.tsx` |
| **`/hanok`** | `HANOK-F004` | `VillageDetailModal` | `DATA-HANOK-002` | Place Overview | `BE-REQ-002` (`GET /api/v1/hanoks/{id}`) | `src/hanok/components/VillageDetailModal.tsx` |
| **`/map`** | `MAP-F001` | `PlaceList` / `KakaoMap` | `DATA-MAP-001` | Place / Location | `BE-REQ-004` (`GET /api/v1/places/nearby`) | `src/map/components/PlaceList.tsx` |
| **`/map`** | `MAP-F002` | `WarmthLayer` / `HeatCanvas` | `DATA-MAP-003` | Heat Congestion | `BE-REQ-005` (`GET /api/v1/map/congestion/heatmap`) | `src/map/components/WarmthLayer.tsx` |
| **`/map`** | `MAP-F003` | `WarmthFeed` / `WriteModal` | `DATA-MAP-002` | Warmth Review | `BE-REQ-006`, `BE-REQ-007` (`/places/{id}/warmths`) | `src/map/components/warmth/WarmthFeed.tsx` |
| **`/map`** | `MAP-F004` | `PlaceDetail` / `RoadviewModal` | `DATA-MAP-004` | Place Detail | `BE-REQ-004` | `src/map/components/PlaceDetail.tsx` |
| **`/odii`** | `ODII-F001` | `OdiiAudioFeature` | `DATA-ODII-001` | Audio Track | `BE-REQ-008` (`GET /api/v1/odii/stories`) | `src/features/odii-audio/components/OdiiAudioFeature.tsx` |
| **`/odii`** | `ODII-F002` | `ScriptSyncViewer` | `DATA-ODII-001` | Subtitle Script | `BE-REQ-008` | `src/features/odii-audio/components/ScriptSyncViewer.tsx` |
| **`/odii`** | `ODII-F003` | `SoundConstellationSection` | `DATA-ODII-002` | Constellation | `BE-REQ-008` | `src/features/odii-audio/components/SoundConstellationSection.tsx` |
| **`/odii`** | `ODII-F004` | `OdiiQuestionAssistant` | `DATA-ODII-003` | LLM Docent Q&A | `BE-REQ-009` (`POST /api/v1/odii/ask`) | `src/features/odii-audio/components/OdiiQuestionAssistant.tsx` |
| **`/hanok`** | `HANOK-F005` | `KCultureThemeFeed` | `DATA-HANOK-003` | Screen Hanok (K-Content) | `BE-REQ-010` (`GET /api/v1/hanoks/screen-hanok`) | `src/features/hanok-archive/components/KCultureThemeFeed.tsx` |
