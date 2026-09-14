# Page Spec: 온마루 3D 랜딩 스토리텔링 (`/`)

> **문서 ID**: `PAGE-SPEC-LAND-001`  
> **Route Path**: `/`  
> **Entry File**: `src/app/page.tsx`  
> **Canonical Component**: `src/LandingExperience.jsx`

---

## 1. Page Purpose & Capabilities

"왜 지금 한옥이 필요한가"를 하나의 긴 스크롤(2000vh)을 통해 3D 한옥 메쉬 모델, 절기별 남중고도 일영(그림자) 시뮬레이션, 7단계 전통 건축 부재 결합 인터랙션으로 풀어내는 시네마틱 랜딩 경험을 제공합니다.

### User Capabilities
- **CAP-LAND-01**: 마우스 휠 및 터치 관성 스크롤(Lenis)을 통한 9-Beat 시네마틱 카메라 궤적 이동
- **CAP-LAND-02**: 하지(75.8°)부터 동지(29.0°)까지의 태양 고도각 변화에 따른 실시간 그림자 길이 및 실내 채광 시뮬레이션
- **CAP-LAND-03**: 기단에서 지붕까지 7단계 전통 한옥 축조 과정의 폭파/결합(Exploded View) 애니메이션 감상
- **CAP-LAND-04**: 온마루 철학 서사 확인 후 한옥도감(`/hanok`), 온기지도(`/map`), 마음여행(`/odii`)으로의 라우팅 유도

---

## 2. UI Component Structure

```text
<Home>
  └── <LandingExperience> (Client Orchestrator)
        ├── <LandingLoader> (3D GLB 에셋 로딩 프로그레스 바 & 먹빛 스크린)
        ├── <GlobalBackground progress={progress}> (z: 0, 배경색/비네트 전환)
        ├── <div style={{ height: '2000vh' }} /> (스크롤 스페이서)
        ├── <FixedStage progress={progress}> (z: 1, Three.js 고정 WebGL 캔버스)
        │     └── <Canvas shadows={{ type: PCFShadowMap }} gl={{ alpha: true }}>
        │           ├── <FramedCamera> (구도/FOV/Near/Far 보간)
        │           ├── <SunDriver> (태양광 위치/색온도 매 프레임 업데이트)
        │           ├── <HanokScene> (3D 한옥 메쉬, 그림자 수신 바닥, ContactShadows)
        │           └── <Suspense fallback={<Fallback3DWireframe />}>
        └── <div style={{ position: 'relative', zIndex: 2 }}> (z: 2, 텍스트 레이어)
              ├── <LandingHero progress={progress}> (Beat 1~2: 어둠 속 기와선 태동)
              ├── <LandingSolarShadow progress={progress}> (Beat 3: 절기 남중고도 시뮬레이션)
              ├── <LandingHanokAssembly progress={progress}> (Beat 4: 7단계 부재 축조)
              ├── <LandingPhilosophy progress={progress}> (Beat 5: 비움과 차경의 철학)
              └── <LandingCallToAction progress={progress}> (Beat 6: 서비스 탐색 CTA)
```

---

## 3. Interaction & State Transitions

### 3.1 9-Beat 스크롤 타임라인 맵
- **Beat 1 (0.00 ~ 0.08)**: 흑백 먹빛 어둠 속에서 떠오르는 한옥 실루엣 (Far Camera)
- **Beat 2 (0.08 ~ 0.20)**: 은은한 볕의 태동 및 처마 곡선 클로즈업
- **Beat 3 (0.20 ~ 0.44)**: **절기 그림자 인터랙션** (정면 탑뷰 고정, 광원 고도 이동: 하지 0.25배 → 동지 1.80배 그림자)
- **Beat 4 (0.44 ~ 0.70)**: **7단계 부재 조립** (아이소메트릭 뷰, `assembling: true`, 기단→계단→기둥→마루→벽체→창호→지붕)
- **Beat 5 (0.70 ~ 0.84)**: 한옥 캔버스 페이드아웃 및 "공간을 비워 사람을 채우다" 철학 텍스트
- **Beat 6 (0.84 ~ 1.00)**: 온마루 서비스 탐색 카드 (도감, 지도, 오디오) 및 하단 푸터

---

## 4. Technical Specifications & Optimization

1. **관성 스크롤 (Smooth Scrolling)**:
   - `Lenis` 인스턴스를 통해 스크롤 틱 단위 점프를 방지하고 부드러운 보간 제공 (`src/LandingExperience.jsx#L98-L112`).
2. **WebGL Context & Shadow Map**:
   - `PCFShadowMap` (2048x2048 해상도, `shadow-bias: -0.0001`)을 적용하여 문살과 처마 격자의 정밀한 선형 그림자 렌더링.
3. **에셋 프리로딩**:
   - `useGLTF.preload('/anchae.glb')`로 캔버스 마운트 전 3D 지오메트리 사전 적재. 로딩 중에는 `<Fallback3DWireframe>` 유지.

---

## 5. Implementation Evidence

- **Entry**: `src/app/page.tsx`
- **Main Experience**: `src/LandingExperience.jsx`
- **Model Renderer**: `src/components/HanokModel.jsx`
- **Scene Store**: `src/scroll-core/sceneStore.js`
- **Camera Utils**: `src/scroll-core/cameraUtils.js`
