# Integration Spec: 한국관광 데이터랩 혼잡도 빅데이터 연동

> **통합 대상**: 한국관광 데이터랩 실시간 관광객 수요 집중도 및 혼잡도 API  
> **담당 모듈**: `src/map/warmth/congestion.ts`, `src/map/warmth/heatScale.ts`, `src/map/components/WarmthLayer.tsx`  
> **환경 변수**: `TOUR_API_VISITOR_KEY`, `TOUR_API_CONGESTION_KEY`

---

## 1. Metrics & Data Ingestion

1. **외지인 방문객 수 (`visitorCount`)**: 통신사 기지국 기반 해당 권역/장소의 일일 외지인 유입량.
2. **수요 집중 배율 (`surgeMultiplier`)**: 4주 평균 대비 당일 방문객 증가율 (1.0x ~ 3.5x).
3. **혼잡도 레벨 (`CongestionLevel`)**:
   - `relaxed` (0 ~ 25): 한적하고 고즈넉한 상태
   - `moderate` (26 ~ 50): 적당한 인원
   - `busy` (51 ~ 75): 북적임
   - `surge` (76 ~ 100): 인파 밀집 주의

---

## 2. Heatmap Canvas Rendering

- 위경도 좌표 기준 캔버스(Canvas 2D) 상에 가우시안 방사형 그라디언트(Radial Gradient)를 적용하여 히트 블룸(Bloom) 효과로 시각화.
