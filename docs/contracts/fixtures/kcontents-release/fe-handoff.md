# 신규 탐색 FE 인계 기록 — #691

상태: **BLOCKED (템플릿, 원격 실측 전)**. 이 파일은 FE #366에 이미 전달했다는 기록이 아니다. 실제 staging 검증 뒤 날짜·담당자·증거 링크를 채워 전달한다.

| 항목 | 실제 실행값/증거 |
| --- | --- |
| develop 검증 SHA, staging 이미지 SHA, 실행 시각·담당자 | 미실행 |
| staging API | `https://staging-api.onmaru.site` — 배포·기동 후 확인 |
| 신규 공개 API | [주제·장소·작품·관계 계약](../../discovery-kcontents-api.md), OpenAPI와 fixture는 해당 SHA 기준 |
| discovery active revision 및 이전 revision | 미측정 |
| 100건·1000건 live pilot 보고서, provider 로그, 비용 | 미측정 |
| 검증된 고유 장소 수, 지역·작품 다양성 | 미측정 |
| 공개 관계 수, 수동 판정 수·precision | 미측정 |
| 기존 7개 카드별 작품·장소·근거·권리 재확인 | 미측정 |
| 기존 홈/한옥/지도/상세/찜/Odii/screen-hanok before/after golden | 미실행 |
| retained cursor·철회·revision 전환 | 미실행 |
| TourAPI·검색·worker 장애 및 flag off/rollback | 미실행 |
| 알려진 차이와 FE 처리 | 미정 |

FE는 신규 독립 화면에서 공개 API 응답만 사용한다. 작품별 장소는 `GET /api/v1/discovery/places?workId=...`를 사용한다. 빈 배열·비공개·권리 철회는 정적인 7개 카드를 자동 승인할 근거가 아니다. 인증된 찜과 기존 screen-hanok 경로는 기존 계약을 따른다. 신규 flag가 꺼져 있거나 staging이 중지된 동안 신규 API 가용성을 약속하지 않는다.

인계 전 운영자는 [파일럿 runbook](../../../operations/runbooks/kcontents-rollout.md)의 실측 결과를 첨부하고 FE 담당자에게 실제 환경 주소·활성 revision·비공개 조건·재현 경로를 전달한다. #691은 실측과 staging 리허설이 끝날 때까지 열린 상태로 둔다.
