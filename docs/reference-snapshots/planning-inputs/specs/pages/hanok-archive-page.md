# Page Spec: 한옥도감 (`/hanok`)

> **문서 ID**: `PAGE-SPEC-HANOK-001`  
> **Reference Spec**: 타 페이지 스펙 작성의 기준 모델  
> **Route Path**: `/hanok`  
> **Entry File**: `src/app/hanok/page.tsx`  
> **Canonical Component**: `src/hanok/HanokArchive.tsx`

---

## 1. Page Purpose & Capabilities

전국에 산재된 한옥, 궁궐, 고택, 서원, 전통마을 및 한옥스테이의 문화적/지리적 가치를 공공데이터(TourAPI 4.0)와 연동하여 실시간으로 탐색하고, 계절별 큐레이션 및 지도 기반 인터랙션을 제공하는 디지털 한옥 아카이브입니다.

### User Capabilities
- **CAP-HANOK-01**: 전국 17개 권역별, 4대 한옥 유형(도심형/집성촌형/고택·종택/궁궐 등)별 실시간 목록 검색 및 필터링
- **CAP-HANOK-02**: 이달의 한옥(Monthly Curation) 스토리 및 에디토리얼 콘텐츠 열람
- **CAP-HANOK-03**: 카카오맵 기반 전국 한옥 클러스터 핀 위치 확인 및 선택 동기화
- **CAP-HANOK-04**: 특정 한옥 선택 시 TourAPI 기반 상세 개요, 이용시간, 주차, 홈페이지 확인 모달 표출

---

## 2. UI Component Structure

```text
<HanokPage> (SSR)
  └── <HanokArchive> (CSR)
        ├── <Global styles={paperGround} /> (Emotion 백그라운드)
        ├── <VesselReveal id="intro">
        │     └── <Intro> (타이틀, 리드문, 실시간 수집 카운트)
        ├── <VesselReveal id="monthly">
        │     └── <HanokMonthly> (이달의 큐레이션 카드)
        ├── <ArchiveGroup>
        │     ├── <VesselReveal id="grid">
        │     │     └── <HanokGrid> (검색창, 유형/지역 필터바, 12개 단위 페이지네이션 그리드)
        │     ├── <VesselReveal id="stay">
        │     │     └── <HanokStayAccordion> (한옥스테이 아코디언)
        │     └── <VesselReveal id="map">
        │           └── <HanokMap> (카카오 인터랙티브 맵 프레임)
        ├── <VesselReveal id="manifesto">
        │     └── <HanokManifestoCta> (온마루 한옥 매니페스토)
        └── <VillageDetailModal> (dynamic import, 조건부 렌더링)
```

---

## 3. Interaction & State Transitions

### 3.1 모달 열기/닫기 라이프사이클
- **Open**: `<HanokGrid>`, `<HanokMonthly>`, `<HanokMap>`의 카드/마커 클릭 시 `setSelectedVillage(village)` 호출 -> 모달 dynamic 로드 및 표출.
- **Close**: 모달 닫기 버튼 또는 Backdrop 클릭, `Escape` 키 입력 시 `setSelectedVillage(null)`.

### 3.2 SWR 백그라운드 데이터 동기화
1. SSR 시점 `HANOK_ARCHIVE_FALLBACK` 정적 스냅샷으로 LCP 지연 없이 초기 마운트.
2. 클라이언트 마운트 즉시 `fetch('/api/tourapi')` 호출 (5초 AbortController 타임아웃).
3. 200 OK 수신 시 `decodeHanokArchivePayload` 검증 후 state 갱신.
4. 실패 시 에러 토스트를 띄우지 않고 초기 Fallback 스냅샷을 유지하여 사용자 경험 보호.

---

## 4. Data Flow & Contract Reference

```text
TourAPI 4.0 (External)
    ↓
/api/tourapi (BFF Route Handler)
    ↓
HanokArchiveService.fetchRealtimeHanoks() (Adapter & Normalizer)
    ↓ [DATA-HANOK-001]
HanokArchive Component State ({ villages, meta })
    ↓
HanokGrid / HanokMap / HanokMonthly (UI View)
```

- **Primary Contract**: `DATA-HANOK-001` (`Village`, `VillageMeta`) -> [hanok-village.md](../data-contracts/hanok-village.md)
- **Detail Contract**: `DATA-HANOK-002` (`VillageDetailResponse`)

---

## 5. UI State & Accessibility Policy

- **Loading State**: `VillageDetailModal` 로딩 중 중립 그레이(`#e5e5e3`) 스켈레톤 유지.
- **Empty State**: 검색/필터 결과가 없을 경우 "해당 조건의 한옥을 찾을 수 없습니다" 중립 안내 표출.
- **Motion**: `prefers-reduced-motion` 감지 시 `VesselReveal` 애니메이션을 즉시 완료 상태로 렌더링.

---

## 6. Implementation Evidence

- **Entry**: `src/app/hanok/page.tsx`
- **Main Feature**: `src/hanok/HanokArchive.tsx`
- **Service**: `src/hanok/services/hanokArchive.service.ts`
- **Fallback Data**: `src/hanok/data/hanokArchiveFallback.ts`
- **Types**: `src/hanok/types.ts`
