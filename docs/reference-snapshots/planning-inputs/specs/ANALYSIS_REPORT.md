# OnMaru Frontend (OnMaruFE) 저장소 기반 구현 스펙 및 아키텍처 분석 보고서

> **문서 버전**: v1.0.0  
> **기준 일자**: 2026-09-07  
> **분석 기준**: Implementation-First (실제 소스코드 구현 사실 기반)  
> **문서 목적**: 향후 백엔드(BE) 도메인 모델링, DB/API 설계, 외부 API 어댑터 설계 및 기획/PRD 개선의 SSOT(단일 진실 공급원) 입력 자료

---

## 1. Executive Assessment

1. **구현 중심 아키텍처 완성도**: OnMaruFE는 Next.js 14 App Router 기반으로 3D 스크롤리텔링([LandingExperience.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/LandingExperience.jsx)), 실시간 공공데이터 아카이브 도감([HanokArchive.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/HanokArchive.tsx)), 온기/정보 듀얼 맵([MapPage.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/MapPage.tsx)), 오디오 가이드 도슨트([OdiiAudioFeature.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/OdiiAudioFeature.tsx))의 4대 핵심 축을 갖추고 있습니다.
2. **데이터 계약 및 Fallback 이중화 구조**: 외부 공공 API(한국관광공사 TourAPI, Odii API, DataLab 혼잡도 등) 의존도가 높은 특성을 고려하여 전 영역에 걸쳐 정적 Fallback Snapshot([hanokArchiveFallback.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/data/hanokArchiveFallback.ts), [curatedPlaces.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/data/curatedPlaces.ts), [odiiChapterData.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/data/odiiChapterData.ts))과 디코더/어댑터 레이어가 철저히 구현되어 있습니다.
3. **도메인 모델의 페이지별 파편화(Duplication & Drift)**: 장소(`Place`/`Village`/`Item`)와 좌표(`LatLng`/`mapx, mapy`), 카테고리 분류 체계가 각 도메인 폴더(`src/hanok`, `src/map`, `src/features/odii-audio`)마다 독자적인 인터페이스로 정의되어 있어 공통 백엔드 구축 시 데이터 모델 통합(Consolidation)이 최우선 과제입니다.
4. **Thin Controller & BFF 패턴**: 프론트엔드 내부 Next.js API Routes([`src/app/api/...`](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/api))가 외부 TourAPI 호출 및 어댑테이션을 대행하는 Thin BFF(Backend-for-Frontend) 역할을 수행하고 있어 향후 전용 백엔드(Go/Java/Node)로의 마이그레이션 경로가 매우 명확합니다.
5. **UI State & 접근성 가이드 준수**: [AGENTS.md](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/AGENTS.md)의 UI 가이드(중립 그레이 스켈레톤, 인위적 Step 라벨 금지, `VesselReveal` 스크롤 뷰 복원, `prefers-reduced-motion`)가 컴포넌트 단위로 충실하게 지켜지고 있습니다.

---

## 2. Repository Evidence Map

| 디렉토리 / 경로 | 담당 역할 및 도메인 | 주요 구현 파일 (Evidence) |
| :--- | :--- | :--- |
| **`src/app`** | Next.js App Router 엔트리 및 Thin API Routes | [app/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/page.tsx), [app/hanok/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/hanok/page.tsx), [app/map/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/map/page.tsx), [app/odii/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/odii/page.tsx), [app/api/tourapi/route.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/api/tourapi/route.ts), [app/api/map/warmth/route.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/api/map/warmth/route.ts) |
| **`src/LandingExperience.jsx`** / `src/scroll-core` | 3D 한옥 메쉬 렌더링, 절기 남중고도 그림자, 9-Beat 스토리텔링 | [LandingExperience.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/LandingExperience.jsx), [HanokModel.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/HanokModel.jsx), [LandingSolarShadow.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/landing/LandingSolarShadow.jsx), [LandingHanokAssembly.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/landing/LandingHanokAssembly.jsx) |
| **`src/hanok`** | 전국 한옥 아카이브, 이달의 큐레이션, 인터랙티브 맵, 스테이 아코디언 | [HanokArchive.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/HanokArchive.tsx), [HanokGrid.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokGrid.tsx), [HanokMap.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokMap.tsx), [hanokArchive.service.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/services/hanokArchive.service.ts), [types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/types.ts) |
| **`src/map`** | 카카오맵 기반 정보지도 & 온기지도(Heatmap/Review), 바텀시트, 실시간 피드 | [MapPage.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/MapPage.tsx), [KakaoMap.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/KakaoMap.tsx), [PlaceDetail.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/PlaceDetail.tsx), [WarmthLayer.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/WarmthLayer.tsx), [warmthRepo.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/warmth/warmthRepo.ts), [types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/types.ts) |
| **`src/features/odii-audio`** | 한국관광공사 오디 오디오 도슨트, 대본 싱크 뷰어, 사운드 성좌, LLM Q&A | [OdiiAudioFeature.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/OdiiAudioFeature.tsx), [SoundConstellationSection.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/SoundConstellationSection.tsx), [ScriptSyncViewer.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/ScriptSyncViewer.tsx), [useOdiiAudioPlayer.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/hooks/useOdiiAudioPlayer.ts), [odii.types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/types/odii.types.ts) |
| **`src/shared`** / `src/design-system` | 공통 모션/트랜지션 래퍼, 전역 헤더, 디자인 토큰 | [VesselReveal.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/shared/components/animation/VesselReveal.tsx), [Header.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/shared/components/Header/Header.tsx), [tokens.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/design-system/tokens.ts) |
| **`src/lib`** | 외부 통신 클라이언트 및 로깅 | [tourApiClient.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/lib/tour-api/tourApiClient.ts), [log.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/lib/log.ts) |

---

## 3. Route / Page Inventory

| Route | Entry File | Feature Scope | Data Contracts | External Dependencies | Loading / Fallback State | Priority |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: |
| **`/`** | [src/app/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/page.tsx) | 한옥 3D 인터랙티브 랜딩, 남중고도 일영 시뮬레이션, 7단계 조립 시각화 | `DATA-LAND-001` (SceneStore, Solar) | WebGL, Three.js, `/anchae.glb` 모델 에셋 | [LandingLoader.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/landing/LandingLoader.jsx), [Fallback3DWireframe](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/LandingExperience.jsx#L617-L640) | **P0** |
| **`/hanok`** | [src/app/hanok/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/hanok/page.tsx) | 전국 한옥 아카이브 탐색, 이달의 한옥, 지역/유형 필터링, 카카오 지도 핀 연동, 상세 모달 | `DATA-HANOK-001` (`Village`), `DATA-HANOK-002` (`VillageDetailResponse`) | TourAPI 4.0 (`areaBasedList2`, `detailCommon2`), Kakao Maps SDK | 정적 스냅샷 [HANOK_ARCHIVE_FALLBACK](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/data/hanokArchiveFallback.ts) 선렌더 후 백그라운드 revalidate | **P0** |
| **`/map`** | [src/app/map/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/map/page.tsx) | 정보지도(카테고리 장소 탐색, 로드뷰, 길찾기) & 온기지도(방문객 혼잡도 히트맵, 1줄 온기/상세 리뷰 피드, 온기 작성) | `DATA-MAP-001` (`Item`), `DATA-MAP-002` (`Warmth`), `DATA-MAP-003` (`HeatSpot`), `DATA-MAP-004` (`PlaceDetailData`) | Kakao Maps SDK, TourAPI 4.0, 한국관광 데이터랩 API, LocalStorage | [curatedPlaces.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/data/curatedPlaces.ts), [seed.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/warmth/seed.ts) 로컬 저장소 fallback | **P0** |
| **`/odii`** | [src/app/odii/page.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/app/odii/page.tsx) | 오디 오디오 투어 플레이어, 가사/자막 동기화 뷰어, 공간 챕터 성좌도, LLM Q&A 도슨트 | `DATA-ODII-001` (`OdiiStoryItem`), `DATA-ODII-002` (`OdiiChapterItem`) | TourAPI Odii 오디오 가이드 API, HTML5 Audio, LLM API (`/api/odii/ask`) | [odiiChapterData.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/data/odiiChapterData.ts), [constellationData.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/data/constellationData.ts) Mock Fallback | **P1** |

---

## 4. Feature Inventory

| Feature ID | Page | Capability | Components | Data Contracts | State / Store | Completeness |
| :--- | :--- | :--- | :--- | :--- | :--- | :---: |
| **LAND-F001** | `/` | 9개 Beat 스크롤 연동 카메라/조명 보간 | [LandingExperience.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/LandingExperience.jsx), [FramedCamera](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/LandingExperience.jsx#L270-L293) | `DATA-LAND-001` | Zustand (`useSceneStore`) | 100% (Production) |
| **LAND-F002** | `/` | 절기별 태양 고도각에 따른 그림자 길이 연산 | [LandingSolarShadow.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/landing/LandingSolarShadow.jsx), `SunDriver` | `DATA-LAND-001` | `useSceneStore.sun` | 100% (Production) |
| **LAND-F003** | `/` | 7단계 한옥 부재(기단~지붕) 축 분해/결합 | [LandingHanokAssembly.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/landing/LandingHanokAssembly.jsx), [HanokModel.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/HanokModel.jsx) | `DATA-LAND-001` | `useSceneStore.assembling` | 100% (Production) |
| **HANOK-F001** | `/hanok` | 전국 한옥/고택/서원 목록 그리드 및 검색/정렬 | [HanokGrid.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokGrid.tsx), [FilterBar.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/components/FilterBar.tsx) | `DATA-HANOK-001` | Local State (`useMemo`) | 100% (Production) |
| **HANOK-F002** | `/hanok` | 계절별 대표 큐레이션 한옥 스토리 뷰 | [HanokMonthly.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokMonthly.tsx) | `DATA-HANOK-001` | Props (`villages`) | 100% (Production) |
| **HANOK-F003** | `/hanok` | 마커 클릭 시 해당 한옥 바텀시트/카드 포커스 | [HanokMap.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokMap.tsx), [KakaoMap](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokInteractiveMapFrame.tsx) | `DATA-HANOK-001` | Local State (`activeId`) | 100% (Production) |
| **HANOK-F004** | `/hanok` | TourAPI 상세 개요 및 이용안내 모달 표출 | [VillageDetailModal.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/components/VillageDetailModal.tsx) | `DATA-HANOK-002` | `hanokDetail.service.ts` | 100% (Production) |
| **MAP-F001** | `/map` | 내 위치/중심좌표 반경 주변 관광/문화 장소 조회 | [KakaoMap.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/KakaoMap.tsx), [PlaceList.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/PlaceList.tsx) | `DATA-MAP-001` | Zustand (`useMapStore`) | 100% (Production) |
| **MAP-F002** | `/map` | 관광 빅데이터 혼잡도 히트맵 및 요일 스크러빙 | [WarmthLayer.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/WarmthLayer.tsx), [HeatCanvas.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/warmth/HeatCanvas.tsx) | `DATA-MAP-003` | `useMapStore.heatSpots` | 95% (Mock+API 혼합) |
| **MAP-F003** | `/map` | 사용자 체감 정취(북적/한적) 피드 및 온기 작성 | [WarmthFeed.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/warmth/WarmthFeed.tsx), [WriteWarmthModal.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/warmth/WriteWarmthModal.tsx) | `DATA-MAP-002` | LocalStorage Repo | 95% (Local Persistence) |
| **MAP-F004** | `/map` | 장소 상세 정보 패널, 카카오 로드뷰, 길찾기 연동 | [PlaceDetail.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/PlaceDetail.tsx), [RoadviewModal.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/detail/RoadviewModal.tsx) | `DATA-MAP-004` | `usePlaceDetail` hook | 100% (Production) |
| **ODII-F001** | `/odii` | 오디오 재생/일시정지, 프로그레스 바, 배속 제어 | [OdiiAudioFeature.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/OdiiAudioFeature.tsx), [useOdiiAudioPlayer.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/hooks/useOdiiAudioPlayer.ts) | `DATA-ODII-001` | Zustand (`useOdiiAudioStore`) | 100% (Production) |
| **ODII-F002** | `/odii` | 현재 오디오 재생 시간에 맞춘 자막 자동 스크롤 | [ScriptSyncViewer.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/ScriptSyncViewer.tsx), [scriptParser.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/utils/scriptParser.ts) | `DATA-ODII-001` | `useOdiiAudioStore.currentTime` | 100% (Production) |
| **ODII-F003** | `/odii` | 지역 챕터별 사운드 클러스터 성좌 뷰 | [SoundConstellationSection.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/SoundConstellationSection.tsx) | `DATA-ODII-002` | Local State | 90% (Demo/Mock 연동) |
| **ODII-F004** | `/odii` | 오디오 콘텐츠 기반 실시간 AI 질의응답 | [OdiiQuestionAssistant.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/OdiiQuestionAssistant.tsx), `/api/odii/ask` | `DATA-ODII-003` | Local State | 80% (Route Mock/BFF) |

---

## 5. Data Contract Inventory

| Contract ID | Interface / Type Name | Canonical Source File | Source Type | Fields Overview | Risk | Recommended Spec |
| :--- | :--- | :--- | :--- | :--- | :---: | :--- |
| **`DATA-HANOK-001`** | `Village`, `VillageMeta` | [src/hanok/types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/types.ts#L1-L34) | TourAPI + Static Fallback | `id`, `name`, `rawTitle`, `region`, `addr`, `lat`, `lng`, `type`, `badges`, `image`, `hasImage`, `summary`, `overview` | Low | `docs/specs/data-contracts/hanok-village.md` |
| **`DATA-HANOK-002`** | `VillageDetailResponse` | [src/hanok/types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/types.ts#L40-L53) | TourAPI (`detailCommon2`) | `overview`, `homepage`, `tel`, `usetime`, `restdate`, `parking`, `expguide`, `repeatInfo`, `images`, `source` | Medium | `docs/specs/data-contracts/hanok-detail.md` |
| **`DATA-MAP-001`** | `Item`, `PlaceCategory` | [src/map/types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/types.ts#L28-L41) | TourAPI + Mock | `id`, `name`, `category`, `lat`, `lng`, `addr`, `image`, `tel`, `dist`, `isTraditional` | Medium | `docs/specs/data-contracts/map-place-item.md` |
| **`DATA-MAP-002`** | `Warmth`, `WarmthReview` | [src/map/types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/types.ts#L43-L85) | LocalStorage / BFF API | `id`, `placeId`, `placeName`, `lat`, `lng`, `text`, `mood`, `score`, `tags`, `createdAt`, `visitorCount`, `goodTags`, `badTags` | High | `docs/specs/data-contracts/map-warmth.md` |
| **`DATA-MAP-003`** | `HeatSpot`, `CongestionLevel` | [src/map/types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/types.ts#L129-L157) | DataLab BigData / Mock | `id`, `placeId`, `name`, `lat`, `lng`, `district`, `visitorCount`, `congestionScore`, `congestionLevel`, `surgeMultiplier`, `series` | High | `docs/specs/data-contracts/map-heat-spot.md` |
| **`DATA-MAP-004`** | `PlaceDetailData` | [src/map/types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/types.ts#L98-L113) | TourAPI (`detailIntro2`) | `contentId`, `contentTypeId`, `title`, `overview`, `addr1`, `tel`, `images`, `mapx`, `mapy`, `intro`, `homepage` | Medium | `docs/specs/data-contracts/place-detail.md` |
| **`DATA-ODII-001`** | `OdiiStoryItem`, `ScriptLine` | [src/features/odii-audio/types/odii.types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/types/odii.types.ts#L13-L53) | TourAPI Odii / Mock | `tid`, `tlid`, `stid`, `stlid`, `title`, `audioTitle`, `category`, `mapX`, `mapY`, `script`, `parsedScript`, `playTime`, `audioUrl`, `imageUrl` | Medium | `docs/specs/data-contracts/odii-story.md` |
| **`DATA-ODII-002`** | `OdiiChapterItem` | [src/features/odii-audio/types/odiiChapter.types.ts](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/types/odiiChapter.types.ts) | Static Data / Mock | `chapterId`, `title`, `subtitle`, `region`, `stories`, `coordinates`, `bgImage` | Low | `docs/specs/data-contracts/odii-chapter.md` |
| **`DATA-LAND-001`** | `SceneStoreState`, `SolarNow` | [src/scroll-core/sceneStore.js](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/scroll-core/sceneStore.js) | Client In-Memory | `progress`, `assembling`, `assemblyProgress`, `sun` (`altitude`, `value`) | Low | `docs/specs/data-contracts/landing-scene.md` |

---

## 6. Backend Requirement Candidates & API Matrix

| Req ID | Source Feature | Backend Capability Candidate | HTTP Method & Candidate Endpoint | Read / Write | Pagination / Filter | Persistence Potential | Confidence |
| :--- | :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| **`BE-REQ-001`** | `HANOK-F001` | 한옥 도감 목록 조회 및 필터링 | `GET /api/v1/hanoks` | Read | Yes (카테고리, 지역, 이미지유무) | Persistence Candidate (Cache) | **High** |
| **`BE-REQ-002`** | `HANOK-F004` | 한옥/고택 상세 정보 조회 | `GET /api/v1/hanoks/{id}` | Read | No | Persistence Candidate | **High** |
| **`BE-REQ-003`** | `HANOK-F002` | 이달의 큐레이션 한옥 스토리 조회 | `GET /api/v1/hanoks/curation/monthly` | Read | No | Persistence Candidate | **High** |
| **`BE-REQ-004`** | `MAP-F001` | 반경 기반 주변 장소 검색 | `GET /api/v1/places/nearby` | Read | Yes (`lat`, `lng`, `radius`, `cat`) | Persistence Candidate | **High** |
| **`BE-REQ-005`** | `MAP-F002` | 지역별 관광 혼잡도 히트맵 시계열 조회 | `GET /api/v1/map/congestion/heatmap` | Read | Yes (`region`, `date`) | Cache Candidate (DataLab 연동) | **Medium** |
| **`BE-REQ-006`** | `MAP-F003` | 장소별 온기 피드 조회 | `GET /api/v1/places/{id}/warmths` | Read | Yes (`page`, `size`, `sort`) | Persistence Candidate | **High** |
| **`BE-REQ-007`** | `MAP-F003` | 새로운 1줄 온기/리뷰 작성 | `POST /api/v1/places/{id}/warmths` | Write | No | Persistence Candidate | **High** |
| **`BE-REQ-008`** | `ODII-F001` | 오디 오디오 투어 스토리 목록/상세 | `GET /api/v1/odii/stories` | Read | Yes (`category`, `query`) | Persistence Candidate (TourAPI Adapter) | **High** |
| **`BE-REQ-009`** | `ODII-F004` | AI 도슨트 RAG 질의응답 스트리밍 | `POST /api/v1/odii/ask` | Read/Exec | No | External-only (LLM Service) | **Medium** |

---

## 7. Traceability Matrix

| Page | Feature ID | UI Component | Data Contract | Domain Concept | Backend Requirement Candidate | Code Evidence |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`/`** | `LAND-F001` | `LandingExperience` | `DATA-LAND-001` | 3D Scene / Camera | - (Client Only) | [LandingExperience.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/LandingExperience.jsx#L120-L137) |
| **`/`** | `LAND-F002` | `LandingSolarShadow` | `DATA-LAND-001` | Solar Calculation | - (Client Only) | [LandingSolarShadow.jsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/components/landing/LandingSolarShadow.jsx) |
| **`/hanok`** | `HANOK-F001` | `HanokGrid` | `DATA-HANOK-001` | Hanok Village | `BE-REQ-001` (`GET /api/v1/hanoks`) | [HanokGrid.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokGrid.tsx) |
| **`/hanok`** | `HANOK-F002` | `HanokMonthly` | `DATA-HANOK-001` | Curation Story | `BE-REQ-003` (`GET /api/v1/hanoks/curation/monthly`) | [HanokMonthly.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/sections/HanokMonthly.tsx) |
| **`/hanok`** | `HANOK-F004` | `VillageDetailModal` | `DATA-HANOK-002` | Place Overview | `BE-REQ-002` (`GET /api/v1/hanoks/{id}`) | [VillageDetailModal.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/hanok/components/VillageDetailModal.tsx) |
| **`/map`** | `MAP-F001` | `PlaceList` / `KakaoMap` | `DATA-MAP-001` | Place / Location | `BE-REQ-004` (`GET /api/v1/places/nearby`) | [PlaceList.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/PlaceList.tsx) |
| **`/map`** | `MAP-F002` | `WarmthLayer` / `HeatCanvas` | `DATA-MAP-003` | Heat Congestion | `BE-REQ-005` (`GET /api/v1/map/congestion/heatmap`) | [WarmthLayer.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/WarmthLayer.tsx) |
| **`/map`** | `MAP-F003` | `WarmthFeed` / `WriteModal` | `DATA-MAP-002` | Warmth Review | `BE-REQ-006`, `BE-REQ-007` (`/places/{id}/warmths`) | [WarmthFeed.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/map/components/warmth/WarmthFeed.tsx) |
| **`/odii`** | `ODII-F001` | `OdiiAudioFeature` | `DATA-ODII-001` | Audio Track | `BE-REQ-008` (`GET /api/v1/odii/stories`) | [OdiiAudioFeature.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/OdiiAudioFeature.tsx) |
| **`/odii`** | `ODII-F002` | `ScriptSyncViewer` | `DATA-ODII-001` | Subtitle Script | `BE-REQ-008` | [ScriptSyncViewer.tsx](file:///Users/yangseunghyeon/Development/OnMaru/OnMaruFE/src/features/odii-audio/components/ScriptSyncViewer.tsx) |
