# Feature: 온기모드 (OnGi Mode) — 한국관광공사 빅데이터 광역지자체 방문자수 API

본 문서는 온마루의 '온기모드' 기능에서 **관광지 집중률 데이터와 함께 활용할 광역지자체 실제 방문자수 데이터**의 API 명세를 정리한 문서입니다.

> 💡 집중률(%) API가 "지금 얼마나 붐비는가"를 알려준다면, 이 API는 "실제로 몇 명이 방문했는가"를 알려줍니다.
> 두 데이터를 조합하면 유저에게 훨씬 직관적인 혼잡도 정보를 제공할 수 있습니다.

---

## 1. API 개요

| 항목 | 내용 |
|---|---|
| **서비스명** | 한국관광공사_빅데이터_지역별 방문자수_GW |
| **제공기관** | 한국관광공사 |
| **데이터 출처** | ㈜KT (내국인) · SK텔레콤(주) (외국인) 이동통신 데이터 기반 |
| **Base URL** | `http://apis.data.go.kr/B551011/DataLabService` |
| **데이터 성격** | 일상생활권(거주·통근·통학)을 벗어나 관광 등의 목적으로 일정 시간 머문 사람의 **일자별 순방문자 수** (추정치) |

---

## 2. 엔드포인트

### GET `/metcoRegnVisitrDDList` — 광역지자체 지역 방문자수 집계 데이터 조회

광역지자체(시/도) 단위의 일자별 방문자수 집계 데이터를 조회합니다.

---

## 3. 요청 파라미터 (Request Parameters)

| 파라미터 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| `serviceKey` | string | ✅ | 공공데이터포털 인증키 | (발급받은 키) |
| `MobileOS` | string | ✅ | OS 구분 | `ETC` |
| `MobileApp` | string | ✅ | 서비스명 (앱 이름) | `OnMaru` |
| `startYmd` | string | ✅ | 조회 시작 일자 (YYYYMMDD) | `20260601` |
| `endYmd` | string | ✅ | 조회 종료 일자 (YYYYMMDD) | `20260625` |
| `areaCd` | string | | 광역지자체 코드 (미입력 시 전체 조회) | `1` (서울) |
| `numOfRows` | number | | 한 페이지당 결과 수 | `10` |
| `pageNo` | number | | 페이지 번호 | `1` |
| `_type` | string | | 응답 형식 | `json` |

---

## 4. 응답 데이터 구조

```json
{
  "header": {
    "resultMsg": "string",
    "resultCode": "string"
  },
  "body": {
    "numOfRows": 0,
    "pageNo": 0,
    "totalCount": 0,
    "items": {
      "item": {
        "baseYmd": "string",
        "areaCode": "string",
        "areaNm": "string",
        "daywkDivCd": "string",
        "daywkDivNm": "string",
        "touDivCd": "string",
        "touDivNm": "string",
        "touNum": "string"
      }
    }
  }
}
```

---

## 5. 응답 필드 상세

### Header

| 필드명 | 타입 | 설명 |
|---|---|---|
| `resultCode` | string | 응답 결과 코드 (예: `"0000"` = 성공) |
| `resultMsg` | string | 응답 결과 메시지 (예: `"OK"`) |

### Body

| 필드명 | 타입 | 설명 |
|---|---|---|
| `totalCount` | number | 전체 결과 건수 |
| `numOfRows` | number | 한 페이지당 결과 수 |
| `pageNo` | number | 현재 페이지 번호 |

### Body > Items > Item

| 필드명 | 타입 | 설명 | 예시 |
|---|---|---|---|
| `baseYmd` | string | 기준 일자 (YYYYMMDD) | `"20260624"` |
| `areaCode` | string | 광역지자체 코드 | `"11"` |
| `areaNm` | string | 광역지자체명 (시/도) | `"서울특별시"` |
| `daywkDivCd` | string | 요일 구분 코드 (1:월 ~ 7:일) | `"3"` |
| `daywkDivNm` | string | 요일 구분명 | `"수요일"` |
| `touDivCd` | string | 관광 구분 코드 | `"1"` (현지인), `"2"` (외지인), `"3"` (외국인) |
| `touDivNm` | string | 관광 구분명 | `"외지인"` |
| `touNum` | string | 방문자 수 (순방문자 기준) | `"1234567"` |

---

## 6. 데이터 활용 시 유의사항

### ⚠️ 반드시 인지해야 할 사항

1. **방문자 ≠ 관광객**: 이동통신 데이터 기반 추정치이므로, 관광 목적이 아닌 방문자도 포함될 수 있음
2. **순방문자 집계**: 2박 3일 방문 시 **3명**으로 집계됨 (일자별 순방문자 기준)
3. **데이터 합산 금지**: 광역지자체와 기초지자체는 집계 기준이 다르므로 임의 합산 불가
4. **데이터 지연**: 실시간 데이터가 아닌, 일정 기간 가공 후 제공되는 데이터

---

## 7. 온기모드에서의 활용 방안

### 집중률 API + 방문자수 API 조합

| 데이터 | 출처 | 역할 |
|---|---|---|
| 집중률 (%) | `tatsCnctrRatedList` | 현재/예측 혼잡도 → **"지금 얼마나 붐비나?"** |
| 방문자 수 (명) | `metcoRegnVisitrDDList` | 과거 실측치 → **"보통 몇 명이나 오나?"** |

### 예상 UI 활용 예시

```
🏛️ 서울특별시
├── 오늘 집중률: 75% (보통)
├── 평균 일일 방문자: 약 1,234,567명
├── 📊 "화요일이 가장 한산해요" (요일별 패턴: daywkDivNm 활용)
└── 📈 최근 7일 방문자 추이 그래프
```

---

## 8. 관련 문서

- [온기모드 — 관광지 집중률 API 명세서](./tour_ongi_mode_api.md)
- [온기모드 — 기초지자체 방문자수 API 명세서](./tour_ongi_mode_local_visitor_api.md)
