# Feature: 온기모드 (OnGi Mode) — 한국관광공사 관광지 집중률·방문자 추이 예측 정보 API

본 문서는 온마루의 '온기모드' 기능 구현을 위해 활용하는 **한국관광공사 관광지 집중률 및 방문자 추이 예측 정보 API**의 데이터 구조와 활용 방안을 정리한 명세서입니다.

---

## 1. API 개요

| 항목 | 내용 |
|---|---|
| **API명** | 관광지 집중률 방문자 추이 예측 정보 |
| **제공기관** | 한국관광공사 |
| **기능** | 특정 관광지의 날짜별 집중률(혼잡도) 정보를 조회 |
| **활용 목적** | 온기모드(OnGi Mode) — 관광지의 실시간/예측 혼잡도를 시각화하여 유저에게 최적의 방문 시점을 안내 |

---

## 2. 엔드포인트

### GET `/tatsCnctrRatedList` — 관광지 집중률 정보 목록조회

특정 지역·관광지의 날짜별 집중률(방문자 밀집도) 데이터를 목록으로 조회합니다.

---

## 3. 응답 데이터 구조

```json
{
  "header": {
    "resultMsg": "string",
    "resultCode": "string"
  },
  "body": {
    "totalCount": 0,
    "items": {
      "item": {
        "cnctrRate": "string",
        "baseYmd": "string",
        "areaCd": "string",
        "areaNm": "string",
        "signguCd": "string",
        "signguNm": "string",
        "tAtsNm": "string"
      }
    },
    "numOfRows": 0,
    "pageNo": 0
  }
}
```

---

## 4. 응답 필드 상세

### Header

| 필드명 | 타입 | 설명 |
|---|---|---|
| `resultCode` | string | 응답 결과 코드 (예: `"00"` = 성공) |
| `resultMsg` | string | 응답 결과 메시지 (예: `"NORMAL SERVICE"`) |

### Body

| 필드명 | 타입 | 설명 |
|---|---|---|
| `totalCount` | number | 전체 결과 건수 |
| `numOfRows` | number | 한 페이지당 결과 수 |
| `pageNo` | number | 현재 페이지 번호 |

### Body > Items > Item

| 필드명 | 타입 | 설명 | 예시 |
|---|---|---|---|
| `cnctrRate` | string | 집중률 (혼잡도 수치) | `"75.3"` |
| `baseYmd` | string | 기준 일자 (YYYYMMDD) | `"20260625"` |
| `areaCd` | string | 지역 코드 (시/도) | `"11"` |
| `areaNm` | string | 지역명 (시/도) | `"서울특별시"` |
| `signguCd` | string | 시군구 코드 | `"11010"` |
| `signguNm` | string | 시군구명 | `"종로구"` |
| `tAtsNm` | string | 관광지명 | `"경복궁"` |

---

## 5. 페이지네이션 처리

| 파라미터 | 설명 |
|---|---|
| `pageNo` | 조회할 페이지 번호 (1부터 시작) |
| `numOfRows` | 한 페이지당 결과 수 |
| `totalCount` | 전체 데이터 건수 → `Math.ceil(totalCount / numOfRows)`로 총 페이지 수 계산 |

---

## 6. 에러 처리 가이드

- 응답 수신 시 반드시 `header.resultCode`를 먼저 확인
- `resultCode !== "00"`인 경우 `resultMsg`를 로깅하고 적절한 에러 핸들링 수행
- `body.items`가 `null` 또는 빈 배열인 경우에 대한 방어적 처리 필요

---

## 7. 온기모드(OnGi Mode) 활용 구상

> 💡 '온기(溫氣)'는 따뜻한 기운이라는 뜻으로, 관광지의 '열기(인파 밀집도)'를 직관적으로 느낄 수 있는 모드입니다.

- **히트맵 시각화**: `cnctrRate` 값을 기반으로 지도 위에 혼잡도 히트맵 오버레이
- **최적 방문 시점 추천**: 날짜별 집중률 추이를 분석하여 한산한 시간대/요일 추천
- **지역별 비교**: `areaNm` + `signguNm` 기준으로 지역 간 혼잡도 비교 기능
