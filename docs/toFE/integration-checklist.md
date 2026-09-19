# FE Integration Checklist

각 항목은 backend 구현 완료를 뜻하지 않는다. FE는 fixture 우선으로 상태와 reducer를 만들고, 이후 동일 fixture를 실제 serializer contract test에 연결한다.

## `/discover`

- [ ] `POST /explorations`, `POST /explorations/{id}/turns`의 202 `eventsUrl` 처리
- [ ] SSE `run.stage`, `run.terminal`, `heartbeat`, `reset` parsed-frame reducer
- [ ] terminal/reset/reconnect/탭 복귀 때 GET run+exploration 재동기화
- [ ] EventSource/fetch SSE 미지원 환경에서만 polling fallback
- [ ] `CLARIFICATION_REQUIRED`, `NO_RESULTS`, `FAILED`, `CANCELLED` 화면
- [ ] `engine=BASELINE` 기본 탐색 결과와 기존 board 유지
- [ ] active run 중 action/save 409, cancel/completion 경쟁, stale `baseVersion` 409
- [ ] pending proposal 유지/추가/제외 preview와 APPLY/DISMISS

## `/map` VisitReview

- [ ] 전국→시도→시군구 `visit-review-regions` 집계 drilldown
- [ ] 지도 이동/zoom 중 후기 목록 요청 0회
- [ ] `이 지역 후기 보기` 뒤 REGION 목록·핀 원자적 교체
- [ ] ALL/REGION cursor와 `hasMore:false` 종료
- [ ] 300자/5줄 작성 validation 및 개인정보/광고 금지 안내
- [ ] 작성자 삭제, 타인 삭제 404, 본인 좋아요 403
- [ ] PUT/DELETE 좋아요 optimistic rollback
- [ ] 신고 reason, 중복 신고 receipt, 숨김/삭제 후기 목록 제외

## 관광 장소 찜·월간 타임라인·인증

- [ ] 한옥 목록, 한옥 상세, 지도 선택 카드, Odii 연결 장소 카드가 동일 canonical `placeId`를 사용
- [ ] 한 화면의 `PUT /saved-resources/places/{placeId}` 뒤 다른 화면도 다음 조회에서 `savedByMe:true`를 표시
- [ ] 장소가 한옥·숙박·카페·체험·시장·일반 관광지여도 같은 PLACE API와 동일한 하트 동작 사용
- [ ] 비회원 하트 클릭은 로그인 intent만 보존하고, Kakao 로그인 성공 뒤 공개 상태를 재검증한 같은 PUT을 한 번 실행
- [ ] 로그인 취소·실패, 401, 404, 409, 네트워크 실패 때 optimistic state를 서버 truth로 복구
- [ ] Odii 연결 장소는 PLACE로 저장하고, 연결 없는 독립 story만 `ODII_STORY` 보조 저장 사용
- [ ] `GET /me/timeline` month/day group/`unavailableCount`, 장소명·category·region·찜 시각 렌더
- [ ] 타임라인 PLACE target은 현재 공개 장소 상세로 이동하고, 찜 해제 뒤 다음 조회에서 해당 item 제거
- [ ] 비공개 장소는 과거 카드 정보로 대체하지 않고 `unavailableCount`만 표시
- [ ] `/auth/csrf` header를 unsafe request에 적용
- [ ] Kakao 로그인 취소/실패 뒤 guest board 유지
- [ ] 로그아웃/탈퇴 뒤 개인 cache 제거

## FE-BE 공동 fixture

| Fixture | 통과 조건 |
|---|---|
| journey normal → terminal | SSE terminal 뒤 GET snapshot으로 board 렌더 |
| journey reset/reconnect | 오래된 event ID, deploy restart, network close에서 snapshot 정합 |
| journey provider degraded | 동일 run `engine=BASELINE`, provider 재호출 0회 |
| journey clarification | 지역/조건 질문을 새 turn으로 제출 |
| map region switch | 늦은 이전 응답이 새 목록을 덮어쓰지 않음 |
| review moderation | HIDDEN/REMOVED가 public list/detail/cache에 없음 |
| saved/timeline | private response와 타인 접근이 cache/DTO에서 섞이지 않음 |
| saved place cross-surface | 한옥 상세에서 찜한 canonical place가 지도·Odii 연결 카드에서도 `savedByMe:true` |
| saved place login intent | 비회원 intent는 로그인 성공 뒤 1회만 저장되고, 취소·실패 때 서버 row 0개 |
| saved place unavailable | 삭제·비공개·권리 변경 장소는 목록/timeline item 대신 `unavailableCount`만 반영 |

## 완료 선언 기준

한 화면이 완료되었다고 하려면 mock만 보이는 상태가 아니라, 해당 fixture가 FE reducer와 backend serializer에서 같은 enum·필드·오류 코드를 통과해야 한다. UI 문구는 code/engine을 바탕으로 FE가 소유하며, FastAPI/Gemini 내부 오류를 그대로 노출하지 않는다.
