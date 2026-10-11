# 운영 실데이터 시연 후보 (#691)

이 문서는 운영 DB 읽기 전용 조회와 관광공사 원문 대조 결과다. 운영 DB에 라벨을 넣었다는 기록이 아니며, `HUMAN_VERIFIED` 판정을 대신하지 않는다. 기사 본문을 서비스에 전재하지 않고 출처 URL과 사실 관계만 기록한다.

| 장소 / 작품 | 근거 후보 | 현재 판정 |
| --- | --- | --- |
| 안동 만휴정 / 드라마 `미스터 션샤인` | [한국관광공사 여행기사](https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=d4594ee6-ca20-4083-87fa-f22670b1c5f2): 만휴정을 작품 촬영지로 명시 | `p-tourapi-126998`, 경북 안동시 길안면 묵계하리길 42, `ACTIVE`. 공개 승인·관계 검수 미완료 |
| 강릉 선교장 / 드라마 `사임당 빛의 일기` | [한국관광공사 여행기사](https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=50441e4a-309d-4fcc-bcbd-81cb2affcef6): 선교장을 작품 촬영지로 명시 | `p-tourapi-125800`, 강릉시 운정길 63, `ACTIVE`. 공개 승인·관계 검수 미완료 |
| 남원 광한루원 / 드라마 `옷소매 붉은 끝동` | [한국관광공사 여행기사](https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=2408dce3-d53c-43d8-8cd3-915488da62d1): 광한루원을 작품 촬영지로 명시 | `p-tourapi-126397`, 남원시 요천로 1447, `ACTIVE`. FE fallback의 `슈룹·구르미 그린 달빛` 주장과 별개. 공개 승인·관계 검수 미완료 |

운영 조회에서 7개 대상은 모두 발견됐다. `양진당` 이름 검색은 **상주 양진당**과 **안동 하회 양진당** 두 곳을 반환하므로 contentId `2595672`와 안동 주소를 대조해야 한다. 운영 `k_contents`와 `k_content_place_relations`는 아직 존재하지 않았다. FE fallback의 나머지 작품–장소 주장은 별도로 검증한다. 화보는 초기 `FILMING_LOCATION` 공개 범위에 넣지 않는다. 스키마 배포·근거 검수·selected discovery 승인 후에만 공개 라벨을 입력한다.
