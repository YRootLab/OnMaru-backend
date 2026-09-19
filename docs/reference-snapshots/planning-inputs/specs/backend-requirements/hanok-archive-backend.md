# Backend Requirement: 한옥 아카이브 서비스 (`BE-REQ-HANOK`)

> **Requirement Group**: `BE-REQ-HANOK`  
> **Source Feature**: `HANOK-F001`, `HANOK-F002`, `HANOK-F004`  
> **Target Consumer**: `/hanok` Page, Mobile Client  
> **Status**: Candidate for Backend Sprint 1

---

## 1. Requirement Inventory

| Requirement ID | Capability | HTTP Method & Candidate Endpoint | Read/Write | Cache Strategy |
| :--- | :--- | :--- | :---: | :--- |
| **`BE-REQ-001`** | 한옥 도감 목록 조회 및 필터링 | `GET /api/v1/hanoks` | Read | Redis 1시간 TTL (또는 일일 배치 갱신) |
| **`BE-REQ-002`** | 한옥 상세 정보 조회 (개요, 주차, 이용시간) | `GET /api/v1/hanoks/{id}` | Read | Redis 24시간 TTL |
| **`BE-REQ-003`** | 이달의 한옥 큐레이션 목록 조회 | `GET /api/v1/hanoks/curation/monthly` | Read | Redis 24시간 TTL |

---

## 2. API Specification Details (`BE-REQ-001`)

### 2.1 Request Parameters
- `region`: string (optional, e.g. "서울", "경북")
- `type`: string (optional, e.g. "사대부 고택", "한옥스테이")
- `hasImage`: boolean (optional)
- `keyword`: string (optional, 검색어)
- `page`: integer (default: 1)
- `size`: integer (default: 20)

### 2.2 Response Body (200 OK)
```json
{
  "data": [
    {
      "id": "126508",
      "name": "강릉 선교장",
      "region": "강원",
      "addr": "강원특별자치도 강릉시 운정길 63",
      "lat": 37.7865,
      "lng": 128.8872,
      "type": "사대부 고택",
      "badges": ["국가지정", "고택"],
      "image": "https://tong.visitkorea.or.kr/cms/resource/...",
      "hasImage": true,
      "summary": "강릉 선교장 - 강원특별자치도 강릉시 운정길 63"
    }
  ],
  "meta": {
    "total": 350,
    "page": 1,
    "size": 20,
    "generatedAt": "2026-09-07T14:30:00Z"
  }
}
```

---

## 3. Non-Functional & Resilience Requirements

1. **외부 TourAPI 장애 격리 (Fault Tolerance)**:
   - 외부 공공데이터 포털의 지연 및 장애 발생 시 프론트엔드로 에러를 전파하지 않고, 백엔드 DB/캐시에 저장된 최신 스냅샷을 서빙(Stale-While-Revalidate).
2. **배치 동기화 (Background Sync)**:
   - 매일 심야(KST 03:00) TourAPI 동기화 크론 작업을 수행하여 최신 장소/이미지/정보 업데이트.
3. **위경도 유효성 검증**:
   - 백엔드 적재 시 위경도 좌표의 대한민국 영토 포함 여부 검증 및 좌표계 정규화.
