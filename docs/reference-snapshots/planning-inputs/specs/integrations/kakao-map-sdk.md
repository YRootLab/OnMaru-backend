# Integration Spec: 카카오맵 Web SDK 연동

> **통합 대상**: Kakao Maps JavaScript API v2 (`//dapi.kakao.com/v2/maps/sdk.js`)  
> **담당 모듈**: `src/map/hooks/useKakaoMap.ts`, `src/hanok/sections/HanokInteractiveMapFrame.tsx`  
> **환경 변수**: `NEXT_PUBLIC_KAKAO_MAP_API_KEY`

---

## 1. Lifecycle & Resource Loading

- **클라이언트 전용 로드**: SSR hydration mismatch 방지를 위해 `useEffect` 내에서 스크립트 비동기 주입 및 `kakao.maps.load()` 콜백 후 맵 인스턴스 초기화.
- **클러스터러 라이브러리**: `libraries=services,clusterer` 파라미터를 추가하여 수백 개 마커의 고성능 클러스터링 지원.
- **로드뷰(Roadview)**: `kakao.maps.Roadview` 및 `kakao.maps.RoadviewClient`를 통해 특정 한옥/장소의 360도 실경 로드뷰 모달 연동.
