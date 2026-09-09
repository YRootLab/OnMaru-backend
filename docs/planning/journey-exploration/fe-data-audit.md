# 화면 명칭과 실제 데이터 공급을 분리한다

분석 기준과 한계는 [README](README.md)를 따른다. 아래 경로는 모두 FE 저장소 상대경로다. `공급자 원본`, `가공 지표`, `편집 콘텐츠`, `사용자 생성`, `데모`를 같은 신뢰 등급으로 취급하지 않는다.

## 소스 추적표

| 화면/기능 | 확인한 소스 | 현재 실제 동작 | 서버 설계에 미치는 영향 |
|---|---|---|---|
| 여정 탐색 탭 | `src/app/discover/page.tsx`, `src/features/journey-curator/store/useJourneyStore.ts`, `data/curatedJourneys.ts` | 5개 고정 mood plan, 키워드 매칭, 280/320ms 타이머. AI 호출 아님 | 초기 데모를 실제 검색·계획 API로 치환하되 mock 표식을 유지 |
| AI 여정 지식 그래프 | `src/features/journey-curator/components/KnowledgeGraphView.tsx`, `types/journey.types.ts` | 정해진 x/y 비율의 노드와 edge, 선택 상태 | x/y는 지도 좌표가 아님. 서버는 관계 의미를, FE는 배치를 소유 |
| 여정 카드 | `src/features/journey-curator/components/BentoJourneyGrid.tsx` | 고정 route/hanok/odii/warmth 4종. graph selectedNodeId와 카드가 자동 연동되는 구조는 확인되지 않음 | 공통 canonical entity ref와 selection coordinator 필요 |
| 인기 장소 | `src/app/api/popular-places/route.ts`, `src/map/services/popular.service.ts` | TourAPI `searchKeyword2` arrange P/Q 두 목록을 순서대로 합쳐 중복 제거, 상위10, 캐시10분 | 전역 정렬·주간 집계 아님. 현재 “실시간 인기 장소”를 실제 주간 TOP5로 설명하면 안 됨 |
| 추천 한옥·월간 큐레이션 | `src/hanok/sections/HanokMonthly.tsx` | 월별 contentId와 편집 문구 정적 정의. ID 불일치 시 키워드 또는 첫 장소 fallback | 게시물과 장소 ref 분리. 다른 장소에 같은 편집 문구를 붙이는 fallback 금지 |
| 운영자 큐레이션 | `src/app/admin/curation/page.tsx` | mock 목록, 컴포넌트 상태와 적용 시각 | 인증·편집·검수·게시 서버 기능은 별도 필요 |
| 이번 주 오디 | `src/features/odii-audio/components/odiiInitialLoad.ts`, `OdiiAutoSliceRail.tsx` | 첫 아카이브 12개 중 최대7개 활용. 주간 인기 안내 문구가 있지만 주간 이벤트 집계 근거 없음 | weekly editorial 또는 측정된 weekly popular를 구별 |
| 오디 게시형 레일 | `OdiiEditorialRail.tsx`, `EditorialStoryList.tsx`, `FeaturedStoryRail.tsx` (같은 components 디렉터리) | 오디오 있는 목록을 잘라 카드·목록으로 표현 | 게시형 UI가 곧 Article DB 존재 증거는 아님. 신규 요구로 편집 콘텐츠 모델 도입 |
| 오디 추천 어댑터 | `src/features/odii-audio/api/recommendationAdapter.ts` | 선택적 WHALEBE URL 호출, 없으면 목록 조회 후 랜덤 선택/후보 | 운영 중 개인화·HF 모델이 있다는 근거 아님. 랜덤을 AI 추천으로 표기하지 않음 |
| 오디 sector 4 | `src/features/odii-audio/components/SoundConstellationSection.tsx` | SVG 한국 지역도, 지역 선택→이야기 페이지 조회, 세로 목록/재생, hover→지역 강조 | 지역 중심점과 실제 POI 구분. 정밀 장소 핀과 일대일 매핑되지 않은 오디는 지역 수준으로 표시 |
| 온기 글 | `src/app/api/map/warmth/route.ts`, `src/map/warmth/seed.ts`, `warmthRepo.ts` | GET seed 목록, 사용자 기록·도움 상태 localStorage | 공공 API의 현장 후기 아님. 서버 UGC와 데모를 분리하고 동의 없는 로컬 기록 자동 업로드 금지 |
| 온기 지도 열 | `src/app/api/map/heat/route.ts`, `src/map/services/visitor.service.ts` | DataLab 지역 일별 방문 집계와 FE 파생 점수, 장소/지역/seed 좌표로 표현 | 데이터 날짜·행정코드·공간 해상도·산식을 서버가 명시. 장소 실측으로 둔갑시키지 않음 |
| 현재 도슨트 | `src/features/odii-audio/api/odiiAssistant.service.ts`, `src/app/api/odii/ask/route.ts` | question+filters 요청, answer+sources 응답. 환경변수 기반 외부 RAG 연결 | specs의 selected-story 계약과 다름. 기존 소비자 호환 없이 덮어쓰면 안 됨 |

## 공공 온기와 사람의 온기는 별개다

DataLab 자료는 시군구·일자 단위 집계다. 현재 FE는 주소에서 지역 이름을 찾고 방문자/지역민 비율과 전체 볼륨을 조합한다. `surge`는 4주 기준선 대비 증감률이 아니라 비율에서 만든 값이다. 확대 지도에서 지역 값을 개별 장소 좌표에 놓는다고 장소별 혼잡 측정이 되지 않는다.

`visitor.service.ts`의 오류 fallback에는 상수 값과 당일 날짜가 들어간다. 시계열의 초기 0은 결측과 실제 0을 구분하지 못한다. 지역명만 사용한 키는 동명 지역을 충돌시킬 수 있다. 마지막 페이지가 최신 날짜라는 가정과 대량 첫 페이지 조회도 원본 정렬·페이지 수 검증이 필요하다.

서버는 다음 메타데이터를 의무 제공한다.

| 필드 | 의미 |
|---|---|
| origin | PUBLIC_OBSERVATION / DERIVED_INDEX / USER_POST / EDITORIAL / DEMO |
| status | AVAILABLE / STALE / MISSING / DEMO. 결측은 value=null |
| observedFrom/To | 실제 관측 기간. retrievedAt과 다름 |
| spatialLevel / regionCode | SIGUNGU 등 원본 해상도와 충돌 없는 행정 식별자 |
| sourceRef / methodologyVersion | 원본 근거와 파생 산식 버전 |
| value / unit | 원본 숫자 또는 파생 지수. “혼잡도 24%” 같은 근거 없는 퍼센트 금지 |

이름도 분리한다. 사용자 글은 **여행자가 남긴 온기**, 공공 집계는 **지역 방문 흐름**이다. 파생 온기 지수를 유지한다면 공식 지표가 아닌 OnMaru 가공 지수임을 설명한다. 추천 이유는 “최근 집계에서 방문량이 상대적으로 낮은 지역”까지이고 “지금 이 장소는 한산함”을 보장하지 않는다.

## 기존 계약 정정

현재 FE `/api/odii/ask`의 실제 JSON은 아래와 같다. 상위 설계에서 검토한 `{storyId, storyTitle, scriptContext, question}`을 현재 운영 계약으로 오인하지 않는다.

```json
{
  "question": "이 지역의 한옥 이야기를 알려줘",
  "filters": {"category": "전체", "query": "한옥"}
}
```

현재 응답은 `{answer, sources:[{stid,title,locationName?,formattedDuration?}]}`이며 소비자는 비어 있지 않은 답과 출처를 기대한다. 새 탐색 API는 `/api/v1/explorations`에서 별도로 시작한다. 기존 도슨트의 자료 부족 응답·선택 이야기 명시 기능을 바꾸려면 별도 계약 테스트와 FE 변경 이슈가 필요하다.

## 데이터 정합성·보안 우선 조치

1. `visitor.service.ts`의 서비스 키 기본값 하드코딩을 제거하고 노출 여부 점검 및 필요 시 교체한다. 키 값은 이 보고서에 복제하지 않는다. `.gitignore`는 이미 추적된 소스의 키 유출을 해결하지 못한다.
2. Source ID를 canonical ID와 구분한다. Tour contentid, Odii stid 및 원본 복합 식별자, 공공 행정코드는 provider namespace를 붙여 저장한다.
3. 좌표가 없는 이야기에는 지역 카드만 제공한다. 임의 장소·뷰포트 중앙을 사실상의 관측 위치로 표시하지 않는다.
4. 주간·실시간·AI·혼잡도라는 라벨은 실제 데이터 의미와 일치해야 한다. seed/랜덤/편집을 측정된 순위로 바꾸어 설명하지 않는다.
5. 텍스트·이미지·음원의 공개 열람 가능성과 재배포·임베딩·가공 허용은 별도 확인한다. 허용되지 않은 원문은 인덱싱하지 않는다.

이 감사는 위험을 발견한 정적 분석이다. FE 코드를 수정하거나 공급자 키로 API를 호출하지 않았다. 데이터 수집 성공률이나 실제 누락 건수는 아직 측정하지 않았다.
