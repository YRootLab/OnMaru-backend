# 오디 지역 그룹 API (지도로 듣는 이야기)

소리마루 "지도로 듣는 이야기" 화면의 지역 탭(서울·경기·인천, 강원, 충북 …)에 필요한 광역 지역 그룹 조회 API 계약이다. 관련 이슈: `OnMaru-backend#306`.

## `GET /api/v1/odii/regions`

광역 지역 그룹별 활성 오디오 스토리 수를 조회한다. 호환 경로 `GET /api/v1/audio/regions`도 동일하게 동작한다.

쿼리 파라미터:

| 이름 | 타입 | 설명 |
| --- | --- | --- |
| `language` | string | 기본 `ko-KR`. 스토리가 없는 언어면 `ko-KR`로 폴백하고 `languageStatus: FALLBACK` |

응답 예시:

```json
{
  "schemaVersion": "1.2",
  "language": "ko-KR",
  "languageStatus": "EXACT",
  "groups": [
    { "label": "서울·경기·인천", "regionCodes": ["kr-11", "kr-23", "kr-41"], "storyCount": 5 },
    { "label": "전북", "regionCodes": ["kr-45"], "storyCount": 4 }
  ]
}
```

- `groups`는 스토리가 1건 이상인 그룹만 담고, BE가 고정한 순서(서울·경기·인천 → 강원 → 충북 → 충남·대전·세종 → 경북·대구 → 전북 → 전남·광주 → 경남·부산·울산 → 제주)를 따른다.
- `regionCodes`는 법정동 시도코드(`kr-11` = 서울, `kr-41` = 경기, `kr-45` = 전북 등)다.
- 지역 탭의 스토리 목록은 기존 `GET /api/v1/odii/stories`를 그대로 사용한다. 지역 그룹 단위 조회가 필요하면 그룹에 속한 `regionCodes` 각각으로 요청하거나, 시·군 `regionCode`를 직접 필터로 넘긴다.
- `503 SERVICE_UNAVAILABLE`: Odii 데이터셋 미적재(임시 장애). 빈 목록 + 재시도 UI로 표시하고 가짜 카드로 채우지 않는다.
- `400 INVALID_REQUEST`: `language` 형식 오류.
