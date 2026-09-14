# Backend Requirement: 지도 및 온기 커뮤니티 서비스 (`BE-REQ-MAP`)

> **Requirement Group**: `BE-REQ-MAP`  
> **Source Features**: `MAP-F001`, `MAP-F002`, `MAP-F003`, `MAP-F004`  
> **Target Consumer**: `/map` Page, Mobile App

---

## 1. Requirement Inventory

| Requirement ID | Capability | HTTP Method & Candidate Endpoint | Read/Write | Persistence |
| :--- | :--- | :--- | :---: | :--- |
| **`BE-REQ-004`** | 반경/카테고리 기반 주변 장소 검색 | `GET /api/v1/places/nearby` | Read | RDBMS Spatial Query |
| **`BE-REQ-005`** | 지역별 관광 혼잡도 히트맵 및 시계열 조회 | `GET /api/v1/map/congestion/heatmap` | Read | Redis Cache / DataLab API |
| **`BE-REQ-006`** | 특정 장소의 1줄 온기 및 정취 후기 피드 조회 | `GET /api/v1/places/{id}/warmths` | Read | RDBMS Query |
| **`BE-REQ-007`** | 새로운 1줄 온기 작성 및 등록 | `POST /api/v1/places/{id}/warmths` | Write | RDBMS Insert (`warmth_reviews`) |

---

## 2. API Specifications

### 2.1 주변 장소 검색 (`BE-REQ-004`)
- **Query Params**:
  - `lat`: float (위도)
  - `lng`: float (경도)
  - `radius`: integer (반경 미터, 기본 5000)
  - `category`: string (optional, 'stay', 'culture', 'spot' 등)
- **Response (200 OK)**: `CanonicalPlace[]`

### 2.2 온기 작성 (`BE-REQ-007`)
- **Request Body**:
  ```json
  {
    "mood": "한적",
    "score": 5,
    "text": "비 오는 날 처마 밑에서 듣는 빗소리가 참 좋습니다.",
    "tags": ["고즈넉함", "비오는날", "처마풍경"]
  }
  ```
- **Response (201 Created)**: `Warmth` 생성 객체 반환
