# Integration Spec: 한국관광공사 TourAPI 4.0 연동

> **통합 대상**: 한국관광공사 TourAPI 4.0 (공공데이터포털)  
> **담당 서비스**: `HanokArchiveService`, `TourApiClient`, `PlaceService`  
> **환경 변수**: `TOUR_API_KEY` (Decoding/Encoding 키 대응)

---

## 1. Endpoints & Operations

| Operation | TourAPI 4.0 Endpoint | Purpose | Params |
| :--- | :--- | :--- | :--- |
| **지역기반 목록** | `/B551011/KorService1/areaBasedList1` (또는 2) | 한옥스테이, 전통마을, 고택, 궁궐 목록 수집 | `contentTypeId`, `cat1`, `cat2`, `cat3`, `arrange=P`, `numOfRows=100` |
| **공통 상세정보** | `/B551011/KorService1/detailCommon1` | 장소 개요, 홈페이지, 이미지 정보 | `contentId`, `overviewYN=Y`, `firstImageYN=Y` |
| **소개 상세정보** | `/B551011/KorService1/detailIntro1` | 이용시간, 주차, 휴무일, 체험안내 | `contentId`, `contentTypeId` |
| **오디 오디오 투어** | `/B551011/OdiiAudio/storyBasedList` | 오디오 도슨트 음원 URL 및 자막 대본 | `category`, `langCode=KOR` |

---

## 2. Resilience & Error Handling Policy

1. **타임아웃**: 5000ms AbortController 적용.
2. **에러 격리**: 429(Quota Exceeded), 500(서버 장애) 수신 시 정적 Fallback 데이터 제공 (`hanokArchiveFallback.ts`).
3. **URL 보안 정규화**: 응답 이미지 URL이 `http://`인 경우 `https://`로 강제 치환.
