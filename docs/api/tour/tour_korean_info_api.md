# 한국관광공사 국문 관광정보 서비스 API 명세서

본 문서는 온마루 프로젝트에서 사용하는 한국관광공사 국문 관광정보 서비스 관련 API의 명세서입니다.

---

## 📋 공통 응답 포맷 정의

모든 API 응답의 기본 구조입니다.

### Header (헤더)
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `resultCode` | String | 결과 코드 (`0000`: 성공, 그 외 에러 코드) |
| `resultMsg` | String | 결과 메시지 (`OK` 등) |

### Body (바디)
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `items` | Object/String | 조회된 아이템 목록 객체 (데이터가 없을 경우 빈 문자열 `""` 반환) |
| `numOfRows` | Integer | 한 페이지 결과 수 |
| `pageNo` | Integer | 페이지 번호 |
| `totalCount` | Integer | 전체 데이터 수 |

---

## 📋 관광 정보 공통 응답 필드 정의

관광 관련 API(`areaBasedList2`, `locationBasedList2`, `searchKeyword2`, `areaBasedSyncList2` 등)에서 주로 반환되는 공통 필드 정의입니다.

| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `contentid` | String | 콘텐츠 ID (고유 번호) |
| `contenttypeid` | String | 콘텐츠 타입 ID (12: 관광지, 14: 문화시설, 15: 행사/축제, 28: 레포츠, 32: 숙박, 38: 쇼핑, 39: 음식점 등) |
| `title` | String | 콘텐츠 제목 (관광지명 등) |
| `addr1` | String | 주소 1 (시/도, 구/군 등 기본 주소) |
| `addr2` | String | 주소 2 (상세 주소) |
| `zipcode` | String | 우편번호 |
| `areacode` | String | 지역 코드 |
| `sigungucode` | String | 시군구 코드 |
| `cat1` | String | 서비스 분류 대분류 |
| `cat2` | String | 서비스 분류 중분류 |
| `cat3` | String | 서비스 분류 소분류 |
| `createdtime` | String | 콘텐츠 생성일시 |
| `modifiedtime` | String | 콘텐츠 수정일시 |
| `firstimage` | String | 대표 이미지 URL (원본) |
| `firstimage2` | String | 대표 이미지 URL (썸네일) |
| `cpyrhtDivCd` | String | 저작권 유형 코드 (예: `Type1`, `Type3` 등) |
| `mapx` | String | 경도 (GPS X 좌표) |
| `mapy` | String | 위도 (GPS Y 좌표) |
| `mlevel` | String | 지도 레벨 |
| `tel` | String | 전화번호 |
| `lDongRegnCd` | String | 법정동 대분류 코드 |
| `lDongSignguCd` | String | 법정동 중분류 코드 |
| `lclsSystm1` | String | 대분류 시스템 코드 1 |
| `lclsSystm2` | String | 대분류 시스템 코드 2 |
| `lclsSystm3` | String | 대분류 시스템 코드 3 |

---

## 📋 개별 API 추가 필드 정의

### 1. 행사 정보 (`searchFestival2`) 추가 필드
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `eventstartdate` | String | 행사 시작일 (YYYYMMDD) |
| `eventenddate` | String | 행사 종료일 (YYYYMMDD) |
| `progresstype` | String | 행사 진행 상태 (선택안함 등) |
| `festivaltype` | String | 축제 유형 |

### 2. 소개 정보 (`detailIntro2`) 필드
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `heritage1` | String | 문화재 지정 여부 (1: 지정, 0: 미지정) |
| `heritage2` | String | 세계문화유산 여부 (1: 지정, 0: 미지정) |
| `heritage3` | String | 세계기록유산 여부 (1: 지정, 0: 미지정) |
| `infocenter` | String | 문의처/안내소 |
| `opendate` | String | 개장일 |
| `restdate` | String | 쉬는날 |
| `expguide` | String | 체험안내 |
| `expagerange` | String | 체험가능연령 |
| `accomcount` | String | 수용인원 |
| `useseason` | String | 이용시기 |
| `usetime` | String | 이용시간 (상시 개방 등) |
| `parking` | String | 주차시설 여부 |
| `chkbabycarriage` | String | 유모차 대여 여부 |
| `chkpet` | String | 애완동물 동반 가능 여부 |
| `chkcreditcard` | String | 신용카드 사용 여부 |

### 3. 반복 정보 (`detailInfor2`) 필드
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `serialnum` | String | 일련번호 |
| `infoname` | String | 정보 타이틀 (예: 등산로, 이용가능시설 등) |
| `infotext` | String | 정보 내용 |
| `fldgubun` | String | 필드 구분 (1: 등산로, 2: 코스, 3: 시설 등) |

### 4. 이미지 정보 (`detailImage2`) 필드
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `originimgurl` | String | 원본 이미지 URL |
| `imgname` | String | 이미지 이름 |
| `smallimageurl` | String | 썸네일 이미지 URL |
| `serialnum` | String | 이미지 일련번호 |

### 5. 분류체계코드 (`lclsSystmCode2`) 필드
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `code` | String | 코드 |
| `name` | String | 코드명 |
| `rnum` | String | 일련번호 |
| `lclsSystm1Cd` | String | 대분류 시스템 코드 |
| `lclsSystm1Nm` | String | 대분류 시스템 명칭 |
| `lclsSystm2Cd` | String | 중분류 시스템 코드 |
| `lclsSystm2Nm` | String | 중분류 시스템 명칭 |
| `lclsSystm3Cd` | String | 소분류 시스템 코드 |
| `lclsSystm3Nm` | String | 소분류 시스템 명칭 |

### 6. 관광정보 동기화 목록 (`areaBasedSyncList2`) 추가 필드
| 필드명 | 타입 | 설명 |
| :--- | :--- | :--- |
| `showflag` | String | 표출 여부 (`1`: 표출, `0`: 비표출) |

---

## 📡 API 엔드포인트 명세

### 1️⃣ 지역코드조회
*   **Endpoint**: `GET /areaCode2`
*   **설명**: 지역코드 정보를 조회합니다. (예: 서울, 인천, 대전 등)

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "rnum": 1,
            "code": "1",
            "name": "서울"
          },
          {
            "rnum": 2,
            "code": "2",
            "name": "인천"
          },
          {
            "rnum": 3,
            "code": "3",
            "name": "대전"
          },
          {
            "rnum": 4,
            "code": "4",
            "name": "대구"
          },
          {
            "rnum": 5,
            "code": "5",
            "name": "광주"
          }
        ]
      },
      "numOfRows": 5,
      "pageNo": 1,
      "totalCount": 17
    }
  }
}
```
</details>

---

### 2️⃣ 서비스분류코드조회
*   **Endpoint**: `GET /categoryCode2`
*   **설명**: 대분류/중분류/소분류 등 관광지 서비스의 카테고리 분류 코드를 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "code": "A01",
            "name": "자연",
            "rnum": 1
          },
          {
            "code": "A02",
            "name": "인문(문화/예술/역사)",
            "rnum": 2
          },
          {
            "code": "A03",
            "name": "레포츠",
            "rnum": 3
          },
          {
            "code": "A04",
            "name": "쇼핑",
            "rnum": 4
          },
          {
            "code": "A05",
            "name": "음식",
            "rnum": 5
          }
        ]
      },
      "numOfRows": 5,
      "pageNo": 1,
      "totalCount": 7
    }
  }
}
```
</details>

---

### 3️⃣ 지역기반 관광정보조회
*   **Endpoint**: `GET /areaBasedList2`
*   **설명**: 지역/시군구 및 분류 코드 기준으로 정렬된 관광정보 목록을 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "addr1": "충청남도 공주시 감영길 3 (반죽동)",
            "addr2": "",
            "areacode": "",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2750144",
            "contenttypeid": "38",
            "createdtime": "20210928012320",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "127.121658480839",
            "mapy": "36.4528799330097",
            "mlevel": "6",
            "modifiedtime": "20260226171412",
            "sigungucode": "",
            "tel": "",
            "title": "가가상점",
            "zipcode": "32546",
            "lDongRegnCd": "44",
            "lDongSignguCd": "150",
            "lclsSystm1": "SH",
            "lclsSystm2": "SH05",
            "lclsSystm3": "SH050200"
          },
          {
            "addr1": "부산광역시 부산진구  중앙번영로 (6)",
            "addr2": "",
            "areacode": "",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2805408",
            "contenttypeid": "39",
            "createdtime": "20220125140006",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "129.059799436643",
            "mapy": "35.1447671591569",
            "mlevel": "6",
            "modifiedtime": "20260227145625",
            "sigungucode": "",
            "tel": "",
            "title": "가가와",
            "zipcode": "47361",
            "lDongRegnCd": "26",
            "lDongSignguCd": "230",
            "lclsSystm1": "FD",
            "lclsSystm2": "FD02",
            "lclsSystm3": "FD020200"
          },
          {
            "addr1": "충청남도 공주시 당간지주길 10 (반죽동)",
            "addr2": "(반죽동)",
            "areacode": "34",
            "cat1": "A02",
            "cat2": "A0206",
            "cat3": "A02061000",
            "contentid": "2750143",
            "contenttypeid": "14",
            "createdtime": "20210928012011",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/06/3564906_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/06/3564906_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "127.1219749520",
            "mapy": "36.4521187744",
            "mlevel": "6",
            "modifiedtime": "20251111151027",
            "sigungucode": "1",
            "tel": "",
            "title": "가가책방",
            "zipcode": "32549",
            "lDongRegnCd": "44",
            "lDongSignguCd": "150",
            "lclsSystm1": "VE",
            "lclsSystm2": "VE12",
            "lclsSystm3": "VE120100"
          },
          {
            "addr1": "전라남도 신안군 흑산면 가거도길 38-2",
            "addr2": "",
            "areacode": "38",
            "cat1": "A01",
            "cat2": "A0101",
            "cat3": "A01011300",
            "contentid": "127480",
            "contenttypeid": "12",
            "createdtime": "20030905090000",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/28/3572128_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/28/3572128_image3_1.jpg",
            "cpyrhtDivCd": "Type1",
            "mapx": "125.1263860145",
            "mapy": "34.0520609879",
            "mlevel": "6",
            "modifiedtime": "20251124134437",
            "sigungucode": "12",
            "tel": "",
            "title": "가거도",
            "zipcode": "58866",
            "lDongRegnCd": "46",
            "lDongSignguCd": "910",
            "lclsSystm1": "NA",
            "lclsSystm2": "NA02",
            "lclsSystm3": "NA020500"
          },
          {
            "addr1": "충청북도 청주시 흥덕구 가경동",
            "addr2": "1438",
            "areacode": "33",
            "cat1": "A04",
            "cat2": "A0401",
            "cat3": "A04010200",
            "contentid": "1433504",
            "contenttypeid": "38",
            "createdtime": "20111111014944",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/50/3492550_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/50/3492550_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "127.4341171334",
            "mapy": "36.6286485111",
            "mlevel": "6",
            "modifiedtime": "20250521105928",
            "sigungucode": "10",
            "tel": "",
            "title": "가경 터미널시장",
            "zipcode": "28398",
            "lDongRegnCd": "43",
            "lDongSignguCd": "113",
            "lclsSystm1": "SH",
            "lclsSystm2": "SH06",
            "lclsSystm3": "SH060200"
          }
        ]
      },
      "numOfRows": 5,
      "pageNo": 1,
      "totalCount": 50693
    }
  }
}
```
</details>

---

### 4️⃣ 위치기반 관광정보조회
*   **Endpoint**: `GET /locationBasedList2`
*   **설명**: 요청한 위/경도(GPS 좌표)를 기준으로 특정 반경 내에 존재하는 관광정보를 거리순으로 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": "",
      "numOfRows": 0,
      "pageNo": 1,
      "totalCount": 0
    }
  }
}
```
</details>

---

### 5️⃣ 키워드 검색 조회
*   **Endpoint**: `GET /searchKeyword2`
*   **설명**: 특정 검색어가 관광지 제목에 포함되어 있는 관광정보 목록을 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "addr1": "전라남도 담양군 창평면 돌담길 67",
            "addr2": "",
            "zipcode": "57389",
            "areacode": "",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "845428",
            "contenttypeid": "39",
            "createdtime": "20091029071418",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "127.029059893066",
            "mapy": "35.2406388662513",
            "mlevel": "6",
            "modifiedtime": "20260313171108",
            "sigungucode": "",
            "tel": "",
            "title": "갑을원한옥카페",
            "lDongRegnCd": "46",
            "lDongSignguCd": "710",
            "lclsSystm1": "FD",
            "lclsSystm2": "FD05",
            "lclsSystm3": "FD050100"
          },
          {
            "addr1": "강원특별자치도 강릉시 죽헌길 114 (죽헌동)",
            "addr2": "",
            "zipcode": "25465",
            "areacode": "",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2531222",
            "contenttypeid": "32",
            "createdtime": "20180111220743",
            "firstimage": "https://tong.visitkorea.or.kr/cms/resource/66/3525066_image2_1.jpg",
            "firstimage2": "https://tong.visitkorea.or.kr/cms/resource/66/3525066_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "128.87796587277418",
            "mapy": "37.77733302222703",
            "mlevel": "6",
            "modifiedtime": "20260407170411",
            "sigungucode": "",
            "tel": "",
            "title": "강릉오죽한옥마을",
            "lDongRegnCd": "51",
            "lDongSignguCd": "150",
            "lclsSystm1": "AC",
            "lclsSystm2": "AC03",
            "lclsSystm3": "AC030200"
          },
          {
            "addr1": "전라남도 해남군 옥천면 도림길 81",
            "addr2": "",
            "zipcode": "59022",
            "areacode": "38",
            "cat1": "B02",
            "cat2": "B0201",
            "cat3": "B02011600",
            "contentid": "2574326",
            "contenttypeid": "32",
            "createdtime": "20181206184231",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/38/2573738_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/38/2573738_image2_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "126.6616456733",
            "mapy": "34.5159721453",
            "mlevel": "",
            "modifiedtime": "20241126105059",
            "sigungucode": "23",
            "tel": "",
            "title": "개울한옥민박",
            "lDongRegnCd": "46",
            "lDongSignguCd": "820",
            "lclsSystm1": "AC",
            "lclsSystm2": "AC03",
            "lclsSystm3": "AC030200"
          },
          {
            "addr1": "경상남도 함양군 지곡면 개평길 59",
            "addr2": "",
            "zipcode": "50018",
            "areacode": "36",
            "cat1": "A02",
            "cat2": "A0201",
            "cat3": "A02010600",
            "contentid": "893974",
            "contenttypeid": "12",
            "createdtime": "20091211183048",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/75/3538275_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/75/3538275_image3_1.jpg",
            "cpyrhtDivCd": "Type1",
            "mapx": "127.7673717569",
            "mapy": "35.5658144475",
            "mlevel": "6",
            "modifiedtime": "20251023143000",
            "sigungucode": "20",
            "tel": "",
            "title": "개평한옥마을",
            "lDongRegnCd": "48",
            "lDongSignguCd": "870",
            "lclsSystm1": "HS",
            "lclsSystm2": "HS01",
            "lclsSystm3": "HS010600"
          },
          {
            "addr1": "충청남도 보령시 절터길 41 (요암동)",
            "addr2": "",
            "zipcode": "33491",
            "areacode": "",
            "cat1": "B02",
            "cat2": "B0201",
            "cat3": "B02011600",
            "contentid": "3438571",
            "contenttypeid": "32",
            "createdtime": "20241206121626",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "126.5430885360",
            "mapy": "36.3298338645",
            "mlevel": "6",
            "modifiedtime": "20241218154019",
            "sigungucode": "",
            "tel": "",
            "title": "거북이 한옥",
            "lDongRegnCd": "44",
            "lDongSignguCd": "180",
            "lclsSystm1": "AC",
            "lclsSystm2": "AC03",
            "lclsSystm3": "AC030200"
          }
        ]
      },
      "numOfRows": 5,
      "pageNo": 1,
      "totalCount": 241
    }
  }
}
```
</details>

---

### 6️⃣ 행사정보조회
*   **Endpoint**: `GET /searchFestival2`
*   **설명**: 행사/축제 관련 관광 정보를 기간(시작일/종료일 등) 및 조회 옵션을 설정해 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "addr1": "인천광역시 중구 하늘달빛로2번길 6 (중산동)",
            "addr2": "영종씨사이드파크 하늘구름광장",
            "zipcode": "22409",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2986679",
            "contenttypeid": "15",
            "createdtime": "20230427173710",
            "eventstartdate": "20260505",
            "eventenddate": "20260505",
            "firstimage": "https://tong.visitkorea.or.kr/cms/resource/84/4061184_image2_1.jpg",
            "firstimage2": "https://tong.visitkorea.or.kr/cms/resource/84/4061184_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "126.565811815577",
            "mapy": "37.4817529989573",
            "mlevel": "6",
            "modifiedtime": "20260428175535",
            "areacode": "",
            "sigungucode": "",
            "tel": "032-746-9503",
            "title": "가족의 달 어린이 축제",
            "lDongRegnCd": "28",
            "lDongSignguCd": "110",
            "lclsSystm1": "EV",
            "lclsSystm2": "EV01",
            "lclsSystm3": "EV010600",
            "progresstype": "선택안함",
            "festivaltype": ""
          },
          {
            "addr1": "서울특별시 강동구 올림픽로 875 (암사동)",
            "addr2": "",
            "zipcode": "05239",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "1307813",
            "contenttypeid": "15",
            "createdtime": "20110615014707",
            "eventstartdate": "20261016",
            "eventenddate": "20261018",
            "firstimage": "https://tong.visitkorea.or.kr/cms/resource/77/3541977_image2_1.jpg",
            "firstimage2": "https://tong.visitkorea.or.kr/cms/resource/77/3541977_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "127.13060065465167",
            "mapy": "37.55906143476573",
            "mlevel": "6",
            "modifiedtime": "20260227174111",
            "areacode": "",
            "sigungucode": "",
            "tel": "02-3425-5240",
            "title": "강동선사문화축제",
            "lDongRegnCd": "11",
            "lDongSignguCd": "740",
            "lclsSystm1": "EV",
            "lclsSystm2": "EV01",
            "lclsSystm3": "EV010100",
            "progresstype": "선택안함",
            "festivaltype": ""
          },
          {
            "addr1": "강원특별자치도 강릉시 경포로 365",
            "addr2": "경포 습지광장",
            "zipcode": "25461",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "695592",
            "contenttypeid": "15",
            "createdtime": "20090219004738",
            "eventstartdate": "20260404",
            "eventenddate": "20260411",
            "firstimage": "https://tong.visitkorea.or.kr/cms/resource/23/4041323_image2_1.jpg",
            "firstimage2": "https://tong.visitkorea.or.kr/cms/resource/23/4041323_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "128.895500767487",
            "mapy": "37.7942610311229",
            "mlevel": "6",
            "modifiedtime": "20260521143303",
            "areacode": "",
            "sigungucode": "",
            "tel": "033-640-5130",
            "title": "강릉 경포벚꽃축제",
            "lDongRegnCd": "51",
            "lDongSignguCd": "150",
            "lclsSystm1": "EV",
            "lclsSystm2": "EV01",
            "lclsSystm3": "EV010200",
            "progresstype": "선택안함",
            "festivaltype": ""
          },
          {
            "addr1": "강원특별자치도 강릉시 임영로131번길 6",
            "addr2": "임영관",
            "zipcode": "25534",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2541883",
            "contenttypeid": "15",
            "createdtime": "20180406184032",
            "eventstartdate": "20260814",
            "eventenddate": "20260816",
            "firstimage": "https://tong.visitkorea.or.kr/cms/resource/77/3317777_image2_1.jpg",
            "firstimage2": "https://tong.visitkorea.or.kr/cms/resource/77/3317777_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "128.892094048853",
            "mapy": "37.7532215015504",
            "mlevel": "6",
            "modifiedtime": "20260521143205",
            "areacode": "",
            "sigungucode": "",
            "tel": "033-823-3206",
            "title": "강릉 국가유산 야행",
            "lDongRegnCd": "51",
            "lDongSignguCd": "150",
            "lclsSystm1": "EV",
            "lclsSystm2": "EV01",
            "lclsSystm3": "EV010200",
            "progresstype": "선택안함",
            "festivaltype": ""
          },
          {
            "addr1": "강원특별자치도 강릉시 단오장길 1",
            "addr2": "강릉단오제전수교육관",
            "zipcode": "25586",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "531391",
            "contenttypeid": "15",
            "createdtime": "20080415004012",
            "eventstartdate": "20260615",
            "eventenddate": "20260622",
            "firstimage": "https://tong.visitkorea.or.kr/cms/resource/76/4057376_image2_1.JPG",
            "firstimage2": "https://tong.visitkorea.or.kr/cms/resource/76/4057376_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "mapx": "128.8949883724",
            "mapy": "37.7481582843",
            "mlevel": "6",
            "modifiedtime": "20260611174835",
            "areacode": "",
            "sigungucode": "",
            "tel": "033-651-1593",
            "title": "강릉단오제",
            "lDongRegnCd": "51",
            "lDongSignguCd": "150",
            "lclsSystm1": "EV",
            "lclsSystm2": "EV01",
            "lclsSystm3": "EV010400",
            "progresstype": "선택안함",
            "festivaltype": ""
          }
        ]
      },
      "numOfRows": 5,
      "pageNo": 1,
      "totalCount": 451
    }
  }
}
```
</details>

---

### 7️⃣ 숙박정보조회
*   **Endpoint**: `GET /searchStary2` *(주의: API 표준 명세명은 `/searchStay2`일 수 있습니다)*
*   **설명**: 등록된 숙박 업소 정보를 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": "",
      "numOfRows": 0,
      "pageNo": 1,
      "totalCount": 0
    }
  }
}
```
</details>

---

### 8️⃣ 공통정보조회
*   **Endpoint**: `GET /detailCommon2`
*   **설명**: 특정 콘텐츠 ID를 기준으로 개요(Overview), 홈페이지 등 기본 및 상세 항목을 한번에 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "title": "대학로",
            "createdtime": "20020419090000",
            "modifiedtime": "20250314101454",
            "tel": "",
            "telname": "",
            "homepage": "<a href=\"https://tour.jongno.go.kr/tour/main/contents.do?menuNo=400165\" target=\"_blank\" title=\"새창 : 종로엔 다 있다로 이동\">https://tour.jongno.go.kr/tour/</a>",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/22/3384822_image2_1.JPG",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/22/3384822_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "areacode": "1",
            "sigungucode": "23",
            "lDongRegnCd": "11",
            "lDongSignguCd": "110",
            "lclsSystm1": "VE",
            "lclsSystm2": "VE04",
            "lclsSystm3": "VE040100",
            "cat1": "A02",
            "cat2": "A0203",
            "cat3": "A02030600",
            "addr1": "서울특별시 종로구 대학로 104 (동숭동)",
            "addr2": "",
            "zipcode": "03087",
            "mapx": "127.0023878907",
            "mapy": "37.5805669329",
            "mlevel": "6",
            "overview": "젊음의 열기가 가득한 대학로는 대한민국의 예술, 공연, 자유 등을 대표하는 문화 집결지라 할 수 있다. 대학로가 젊은이들에게 이처럼 아낌없는 사랑을 받았던 것은 서울대학교 문리대와 법대가 자리한 시절부터이다. 서울대학교 학생들을 중심으로 주변 대학 학생들과 젊은이들이 모여들었고, 자연스럽게 다른 어떤 장소와도 비교 불가한 대학로만의 개성을 만들어갔다. 1975년 서울대학교는 관악산 아래로 캠퍼스를 이전했다. 그러나 그 자리에는 아름드리 마로니에 나무가 있어 ‘마로니에공원’이라 이름 붙은 공원이 조성되었다. 이후 젊은이들과 방문자들을 위한 연극, 뮤지컬 등의 크고 작은 문화시설들이 하나 둘 들어세게 되면서 비로소 오늘날의 대학로가 완성되었다. \n서울시는 1985년 5월 대학로를 ‘문화예술의 거리’로, 인사동에 이어 두 번째로 2004년에 ‘문화지구’에 지정했다. 문화지구는 문화자원이 밀집된 장소를 선별해 시장뿐 아니라 정부 차원에서도 보호하고 관리할 필요성을 느껴 선정한다. 대학로의 공연 예술을 활성화시키고 방문객을 늘면서도 상업 관련 시설이 주가 되는 것은 지양하고 소극장 및 문화시설을 보호하기 위함이다. 앞으로도 대학로만의 순수한 낭만과 예술이 번창하는 곳이기를, 대학로의 더욱 빛나는 내일을 소망한다."
          }
        ]
      },
      "numOfRows": 1,
      "pageNo": 1,
      "totalCount": 1
    }
  }
}
```
</details>

---

### 9️⃣ 소개정보조회
*   **Endpoint**: `GET /detailIntro2`
*   **설명**: 특정 콘텐츠 ID의 상세 조건(이용시간, 휴무일, 유모차 동반 가능 여부 등)을 개별 타입에 대응하여 상세하게 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "heritage1": "0",
            "heritage2": "0",
            "heritage3": "0",
            "infocenter": "서울관광안내센터 02-6365-3100",
            "opendate": "",
            "restdate": "연중무휴",
            "expguide": "",
            "expagerange": "",
            "accomcount": "",
            "useseason": "",
            "usetime": "상시 개방",
            "parking": "불가",
            "chkbabycarriage": "불가",
            "chkpet": "",
            "chkcreditcard": "없음"
          }
        ]
      },
      "numOfRows": 1,
      "pageNo": 1,
      "totalCount": 1
    }
  }
}
```
</details>

---

### 🔟 반복정보조회
*   **Endpoint**: `GET /detailInfor2`
*   **설명**: 특정 콘텐츠 ID의 등산로 정보, 입장료 정보, 주요 시설물 등 반복 형태로 표시되는 정보를 멀티 로우 리스트로 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "serialnum": "0",
            "infoname": "등산로",
            "infotext": "",
            "fldgubun": "1"
          },
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "serialnum": "1",
            "infoname": "관광코스안내",
            "infotext": "",
            "fldgubun": "2"
          },
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "serialnum": "2",
            "infoname": "이용가능시설",
            "infotext": "마로니에공원 / 낙산공원 / 박물관 / 갤러리 / 공연장 등",
            "fldgubun": "3"
          },
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "serialnum": "3",
            "infoname": "화장실",
            "infotext": "있음",
            "fldgubun": "3"
          },
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "serialnum": "4",
            "infoname": "입 장 료",
            "infotext": "무료",
            "fldgubun": "3"
          },
          {
            "contentid": "126534",
            "contenttypeid": "12",
            "serialnum": "5",
            "infoname": "한국어 안내서비스",
            "infotext": "",
            "fldgubun": "4"
          }
        ]
      },
      "numOfRows": 6,
      "pageNo": 1,
      "totalCount": 6
    }
  }
}
```
</details>

---

### 1️⃣1️⃣ 이미지정보조회
*   **Endpoint**: `GET /detailImage2`
*   **설명**: 특정 관광지에 등록된 이미지 자료(원본 및 썸네일 경로) 목록을 다중 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "contentid": "126534",
            "originimgurl": "http://tong.visitkorea.or.kr/cms/resource/23/3384823_image2_1.JPG",
            "imgname": "서울_대학로 (2)",
            "smallimageurl": "http://tong.visitkorea.or.kr/cms/resource/23/3384823_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "serialnum": "3384823_5"
          },
          {
            "contentid": "126534",
            "originimgurl": "http://tong.visitkorea.or.kr/cms/resource/24/3384824_image2_1.JPG",
            "imgname": "서울_대학로 (3)",
            "smallimageurl": "http://tong.visitkorea.or.kr/cms/resource/24/3384824_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "serialnum": "3384824_1"
          },
          {
            "contentid": "126534",
            "originimgurl": "http://tong.visitkorea.or.kr/cms/resource/25/3384825_image2_1.JPG",
            "imgname": "서울_대학로 (4)",
            "smallimageurl": "http://tong.visitkorea.or.kr/cms/resource/25/3384825_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "serialnum": "3384825_3"
          },
          {
            "contentid": "126534",
            "originimgurl": "http://tong.visitkorea.or.kr/cms/resource/26/3384826_image2_1.JPG",
            "imgname": "서울_대학로 (5)",
            "smallimageurl": "http://tong.visitkorea.or.kr/cms/resource/26/3384826_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "serialnum": "3384826_6"
          },
          {
            "contentid": "126534",
            "originimgurl": "http://tong.visitkorea.or.kr/cms/resource/27/3384827_image2_1.JPG",
            "imgname": "서울_대학로 (6)",
            "smallimageurl": "http://tong.visitkorea.or.kr/cms/resource/27/3384827_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "serialnum": "3384827_2"
          },
          {
            "contentid": "126534",
            "originimgurl": "http://tong.visitkorea.or.kr/cms/resource/28/3384828_image2_1.JPG",
            "imgname": "서울_대학로 (7)",
            "smallimageurl": "http://tong.visitkorea.or.kr/cms/resource/28/3384828_image3_1.JPG",
            "cpyrhtDivCd": "Type3",
            "serialnum": "3384828_4"
          }
        ]
      },
      "numOfRows": 6,
      "pageNo": 1,
      "totalCount": 6
    }
  }
}
```
</details>

---

### 1️⃣2️⃣ 분류체계코드조회
*   **Endpoint**: `GET /lclsSystmCode2`
*   **설명**: 한국관광공사 TourAPI 대분류/중분류/소분류 체계와 외부 연동 코드 정보를 조회합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "header": {
    "resultMsg": "string",
    "resultCode": "string"
  },
  "body": {
    "items": {
      "item": {
        "lclsSystm3Nm": "string",
        "name": "string",
        "rnum": "string",
        "code": "string",
        "lclsSystm1Cd": "string",
        "lclsSystm1Nm": "string",
        "lclsSystm2Cd": "string",
        "lclsSystm2Nm": "string",
        "lclsSystm3Cd": "string"
      }
    },
    "numOfRows": 0,
    "pageNo": 0,
    "totalCount": 0
  }
}
```
</details>

---

### 1️⃣3️⃣ 관광정보 동기화 목록 조회
*   **Endpoint**: `GET /areaBasedSyncList2`
*   **설명**: 변경되거나 신규 등록된 관광 정보를 기존 로컬 데이터와 동기화하기 위한 배치용 목록을 조회합니다. 노출 여부(`showflag`) 상태값을 제공합니다.

<details>
<summary>💡 응답 예시 (Response JSON)</summary>

```json
{
  "response": {
    "header": {
      "resultCode": "0000",
      "resultMsg": "OK"
    },
    "body": {
      "items": {
        "item": [
          {
            "addr1": "서울 송파구 올림픽로 300¸ 2층",
            "addr2": "",
            "areacode": "1",
            "cat1": "A04",
            "cat2": "A0401",
            "cat3": "A04011000",
            "contentid": "2924134",
            "contenttypeid": "38",
            "createdtime": "20221030182739",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/00/2879100_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/00/2879100_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "127.1040305171",
            "mapy": "37.5142459111",
            "mlevel": "6",
            "modifiedtime": "20240408230251",
            "sigungucode": "18",
            "tel": "",
            "title": "가가밀라노 롯데백화점 에비뉴엘 월드타워점",
            "zipcode": "05551",
            "showflag": "0",
            "lDongRegnCd": "",
            "lDongSignguCd": "",
            "lclsSystm1": "",
            "lclsSystm2": "",
            "lclsSystm3": ""
          },
          {
            "addr1": "충청남도 공주시 감영길 3 (반죽동)",
            "addr2": "",
            "areacode": "",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2750144",
            "contenttypeid": "38",
            "createdtime": "20210928012320",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "127.121658480839",
            "mapy": "36.4528799330097",
            "mlevel": "6",
            "modifiedtime": "20260226171412",
            "sigungucode": "",
            "tel": "",
            "title": "가가상점",
            "zipcode": "32546",
            "showflag": "1",
            "lDongRegnCd": "44",
            "lDongSignguCd": "150",
            "lclsSystm1": "SH",
            "lclsSystm2": "SH05",
            "lclsSystm3": "SH050200"
          },
          {
            "addr1": "부산광역시 부산진구  중앙번영로 (6)",
            "addr2": "",
            "areacode": "",
            "cat1": "",
            "cat2": "",
            "cat3": "",
            "contentid": "2805408",
            "contenttypeid": "39",
            "createdtime": "20220125140006",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "129.059799436643",
            "mapy": "35.1447671591569",
            "mlevel": "6",
            "modifiedtime": "20260227145625",
            "sigungucode": "",
            "tel": "",
            "title": "가가와",
            "zipcode": "47361",
            "showflag": "1",
            "lDongRegnCd": "26",
            "lDongSignguCd": "230",
            "lclsSystm1": "FD",
            "lclsSystm2": "FD02",
            "lclsSystm3": "FD020200"
          },
          {
            "addr1": "충청남도 공주시 당간지주길 10 (반죽동)",
            "addr2": "(반죽동)",
            "areacode": "34",
            "cat1": "A02",
            "cat2": "A0206",
            "cat3": "A02061000",
            "contentid": "2750143",
            "contenttypeid": "14",
            "createdtime": "20210928012011",
            "firstimage": "http://tong.visitkorea.or.kr/cms/resource/06/3564906_image2_1.jpg",
            "firstimage2": "http://tong.visitkorea.or.kr/cms/resource/06/3564906_image3_1.jpg",
            "cpyrhtDivCd": "Type3",
            "mapx": "127.1219749520",
            "mapy": "36.4521187744",
            "mlevel": "6",
            "modifiedtime": "20251111151027",
            "sigungucode": "1",
            "tel": "",
            "title": "가가책방",
            "zipcode": "32549",
            "showflag": "1",
            "lDongRegnCd": "44",
            "lDongSignguCd": "150",
            "lclsSystm1": "VE",
            "lclsSystm2": "VE12",
            "lclsSystm3": "VE120100"
          },
          {
            "addr1": "경기도 양평군 서종면 풀무길 31",
            "addr2": "",
            "areacode": "31",
            "cat1": "B02",
            "cat2": "B0201",
            "cat3": "B02010700",
            "contentid": "983886",
            "contenttypeid": "32",
            "createdtime": "20100323235640",
            "firstimage": "",
            "firstimage2": "",
            "cpyrhtDivCd": "",
            "mapx": "127.3943459429",
            "mapy": "37.6055350250",
            "mlevel": "6",
            "modifiedtime": "20221108174508",
            "sigungucode": "19",
            "tel": "",
            "title": "가가펜션",
            "zipcode": "12501",
            "showflag": "0",
            "lDongRegnCd": "",
            "lDongSignguCd": "",
            "lclsSystm1": "",
            "lclsSystm2": "",
            "lclsSystm3": ""
          }
        ]
      },
      "numOfRows": 5,
      "pageNo": 1,
      "totalCount": 68416
    }
  }
}
```
</details>
