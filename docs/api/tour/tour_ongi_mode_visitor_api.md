# Feature: 온기모드 (OnGi Mode) — 한국관광공사_빅데이터_지역별 방문자수_GW

본 문서는 온마루의 '온기모드' 기능에서 활용할 **한국관광공사 빅데이터 지역별 방문자수 집계 데이터**의 API 명세서입니다. 광역지자체(GW) 및 기초지자체 단위 데이터를 모두 포함하고 있습니다.

> 💡 **온기모드(OnGi Mode) 활용 컨셉**
> - 관광지 집중률(%) 데이터가 "지금 특정 관광지가 얼마나 붐비는가"를 알려준다면, 이 API는 "해당 지역에 실제로 몇 명이 방문하고 있는가"에 대한 구체적인 과거 추이(명)를 제공합니다.
> - 두 데이터를 조회하여 조합함으로써 사용자에게 훨씬 입체적이고 직관적인 관광지 혼잡도 정보를 전달합니다.

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

## 2. 엔드포인트 목록

1. **GET `/metcoRegnVisitrDDList`**
   - 광역 지자체 지역방문자수 집계 데이터 정보 조회
2. **GET `/locgoRegnVisitrDDList`**
   - 기초 지자체 지역방문자수 집계 데이터 정보 조회

---

## 3. 요청 파라미터 (Request Parameters)

| 파라미터 | 타입 | 필수 | 설명 | 예시 |
|---|---|---|---|---|
| `serviceKey` | string | ✅ | 공공데이터포털 인증키 | (발급받은 키) |
| `MobileOS` | string | ✅ | OS 구분 | `ETC` |
| `MobileApp` | string | ✅ | 서비스명 (앱 이름) | `OnMaru` |
| `startYmd` | string | ✅ | 조회 시작 일자 (YYYYMMDD) | `20260601` |
| `endYmd` | string | ✅ | 조회 종료 일자 (YYYYMMDD) | `20260625` |
| `areaCd` | string | | 광역지자체 코드 (광역 조회 시 사용) | `11` (서울) |
| `signguCd` | string | | 기초지자체 코드 (기초 조회 시 사용) | `11110` (종로구) |
| `numOfRows` | number | | 한 페이지당 결과 수 | `10` |
| `pageNo` | number | | 페이지 번호 | `1` |
| `_type` | string | | 응답 형식 | `json` |

---

## 4. [광역] GET `/metcoRegnVisitrDDList` 명세

### 4.1. 응답 데이터 구조 (JSON)

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

### 4.2. 응답 필드 상세

#### Header
| 필드명 | 타입 | 설명 | 예시 |
|---|---|---|---|
| `resultCode` | string | 응답 결과 코드 | `"0000"` (성공) |
| `resultMsg` | string | 응답 결과 메시지 | `"OK"` |

#### Body
| 필드명 | 타입 | 설명 |
|---|---|---|
| `totalCount` | number | 전체 결과 건수 |
| `numOfRows` | number | 한 페이지당 결과 수 |
| `pageNo` | number | 현재 페이지 번호 |

#### Body > Items > Item
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

## 5. [기초] GET `/locgoRegnVisitrDDList` 명세

### 5.1. 응답 데이터 구조 (JSON)

```json
{
  "header": {
    "resultCode": "string",
    "resultMsg": "string"
  },
  "body": {
    "pageNo": 0,
    "totalCount": 0,
    "items": {
      "item": {
        "baseYmd": "string",
        "signguCode": "string",
        "daywkDivCd": "string",
        "signguNm": "string",
        "touDivCd": "string",
        "touDivNm": "string",
        "touNum": "string",
        "daywkDivNm": "string"
      }
    },
    "numOfRows": 0
  }
}
```

### 5.2. 응답 필드 상세

#### Header
| 필드명 | 타입 | 설명 | 예시 |
|---|---|---|---|
| `resultCode` | string | 응답 결과 코드 | `"0000"` (성공) |
| `resultMsg` | string | 응답 결과 메시지 | `"OK"` |

#### Body
| 필드명 | 타입 | 설명 |
|---|---|---|
| `totalCount` | number | 전체 결과 건수 |
| `numOfRows` | number | 한 페이지당 결과 수 |
| `pageNo` | number | 현재 페이지 번호 |

#### Body > Items > Item
| 필드명 | 타입 | 설명 | 예시 |
|---|---|---|---|
| `baseYmd` | string | 기준 일자 (YYYYMMDD) | `"20260624"` |
| `signguCode` | string | 기초지자체 코드 (시군구) | `"11110"` |
| `signguNm` | string | 기초지자체명 (시군구) | `"종로구"` |
| `daywkDivCd` | string | 요일 구분 코드 (1:월 ~ 7:일) | `"3"` |
| `daywkDivNm` | string | 요일 구분명 | `"수요일"` |
| `touDivCd` | string | 관광 구분 코드 | `"1"` (현지인), `"2"` (외지인), `"3"` (외국인) |
| `touDivNm` | string | 관광 구분명 | `"외지인"` |
| `touNum` | string | 방문자 수 (순방문자 기준) | `"54321"` |

---

## 6. 데이터 활용 시 유의사항

1. **방문자 정의**: 이동통신 데이터 기반 추정치이므로, 단순 통근/통학/이동 인원도 일부 포함되어 실제 매표소 등에서 집계하는 수치와는 차이가 있을 수 있습니다.
2. **순방문자 집계**: 일자별 순방문자 기준이므로 한 명이 하루 동안 동일 지자체를 여러 번 오가도 1명으로 집계되나, 2박 3일 체류 방문 시에는 각각의 날짜별로 1명씩(총 3명) 집계됩니다.
3. **광역 vs 기초 데이터 체계**: 광역지자체(`areaCode`) 데이터와 기초지자체(`signguCode`) 데이터는 수집 범위와 집계 분모가 다르므로 하위 기초지자체 데이터를 단순 합산해도 광역지자체 데이터와 정확히 일치하지 않을 수 있습니다. 각각 알맞은 레벨에서 개별 쿼리하여 활용해야 합니다.
4. **일 데이터 업데이트 주기**: 실시간 데이터가 아니며 보통 전일 혹은 수일 전의 집계 데이터가 가공되어 업로드됩니다.

---

## 7. 온기모드 (OnGi Mode) 서비스 구현 시나리오

- **요일별 정체 분석 (Day-of-week Trend)**: `daywkDivNm` 데이터를 수집하여 특정 지역의 요일별 방문객 분포를 분석하고, "이 지역은 보통 화요일이 가장 여유롭습니다" 등의 안내 문구 제공.
- **관광객 비중 확인 (Visitor Composition)**: `touDivNm`를 필터링/비교하여 해당 지역 방문자 중 '현지인' 대비 '외지인/외국인' 비중이 얼마나 높은지 시각화 (진짜 관광 목적으로 붐비는 것인지 판별 가능).
- **관광지 집중률과 결합**:
  - `docs/api/tour/tour_ongi_mode_api.md` 문서의 `tatsCnctrRatedList` (관광지별 집중률)과 결합.
  - 예: "현재 경복궁의 혼잡도(집중률)는 80%로 매우 혼잡합니다. (해당 기초지자체 종로구의 평소 일일 평균 방문객은 12만 명 수준입니다.)"

---

## 8. 관련 문서

- [온기모드 — 관광지 집중률 API 명세서](./tour_ongi_mode_api.md)
