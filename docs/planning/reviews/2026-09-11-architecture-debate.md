# 2026-09-11 architecture debate record

2026-09-11. 요청된 backend-architect-engineer와 backend-developer의 관점을 적용하고 red-blue-team 방식으로 검토했다. 한 작성자가 두 페르소나를 수행했다. 독립 전문가/에이전트 검증이나 사용자 승인으로 간주하지 않는다.

**논제:** OnMaru는 단일 PostgreSQL·Spring 모듈 경계·REST/polling을 유지하면서, 감사 공백과 지도 방문 후기·검색 순위 정책을 이번 planning에 구체화해야 한다.

## Round 1

🔴 Red Team

- 구조 이름만으로 개발자 판단을 줄이지 못한다. 예를 들어 controller가 JPA entity를 그대로 내보내면 ‘헥사고날’이라는 문구가 있어도 FE 변경이 persistence까지 전파된다. consumer-owned port와 runtime/compile 그림을 분리하자.
- 검색 top30 이전에 지역을 정하지 않으면 서울 후보만 잘 가져오는 잘못된 시스템도 ‘검색 성공’으로 보인다. clarification을 정상 outcome으로 만들고 hard filter 뒤 ranking을 두자.
- 지도 전체/지역/내 인근/viewport는 데이터 접근 범위 문제다. 현재위치를 권한으로 착각하지 않도록 별도 파라미터 계약이 필요하다.

🔵 Blue Team

- 경계는 필요하지만 모듈마다 domain/application/web/persistence를 물리적으로 나누면 7일 MVP가 mapper 관리에 소모된다. core와 기술 adapter만 분리하고 catalog 조회는 projection으로 시작하자.
- ranking 가중치를 소수점으로 적는 것만으로 정확도가 좋아지지 않는다. 근거있는 baseline과 held-out 평가가 먼저이며 RAG/model은 선택이어야 한다.
- viewport마다 전체핀을 동기화하려 하면 cluster/event/replay가 따라온다. 현재 페이지의 후기핀만 제공하고 전체 데이터처럼 표시하지 않는 절충이 필요하다.

## Round 2

🔴 Red Team

- Blue의 모듈 폭증 우려를 받아들여 identity/catalog/discovery/journey를 core로, community는 구현 시 추가한다. 대신 app bridge의 호출 방향까지 허용표로 묶어 DI로 숨겨진 runtime cycle을 막는다.
- 가중치의 허위 정밀성을 피하려 v1 초기값/평가 전 상태를 명시하고 exact place intent→weighted feature→diversity 순서를 고정한다. 좋아요와 사진이 없어도 근거가 좋은 장소가 올라오게 한다.
- partial pin 정책은 수용한다. 다만 늦게 도착한 이전 viewport 응답, filter가 바뀐 cursor, 위치권한 거절은 FE가 서로 다르게 처리해야 한다.

🔵 Blue Team

- runtime 호출표는 좋은 보완이지만 저장/탈퇴에서 여러 core가 같은 transaction을 쓰면 완전 독립이라고 주장할 수 없다. 이 결합을 app orchestration에 모으고 lock 순서와 실패 원자성을 명시하자.
- 평가 전 가중치는 수용하되 후보30에 들기 전에 탈락한 정답을 측정해야 한다. retrieval recall과 final nDCG를 나누고 생성 문장 평가를 별개로 두자.
- FE 취소만으로 서버작업이 멈추지 않는다. 지도debounce 외에도 run admission, queue expiry, terminal CAS와 DB pool 예산이 함께 있어야 무료 환경 보호가 된다.

## Round 3

🔴 Red Team

- 저장/탈퇴의 강한 일관성 결합을 인정한다. 같은 DB와 공개 input API를 쓰고 lock 순서를 고정하며 core 간 repository 접근은 막는다. 분산 transaction을 흉내내는 event bus는 추가하지 않는다.
- candidate 단계 recall과 end-to-end 품질을 분리하고 prompt/chunk/rank version을 고정하자. model을 쓰지 않아도 실제 canonical baseline이면 운영 가능하도록 dataMode와 engine을 분리한다.
- Blue의 서버자원 지적을 반영해 worker2/queue2/global4, DB6, deadline20초, QUEUED2초 만료를 초기상한으로 제안한다. LKG도 watermark 문구만으로 충분하지 않으므로 revision pointer+fencing으로 구체화한다.

🔵 Blue Team

- 단일DB transaction은 현재의 유효한 절충이지만 resource 상한은 상품 선정 전 실측이 아니다. 배포 memory/connection limit과 복원drill이 끝나지 않으면 숫자가 있다고 공개 준비를 선언하지 말자.
- 원자적 revision은 부분공개를 막지만 저장공간과 이전revision GC 비용이 있다. dataset별 게시/보존과 source pagination 한계를 공개해야 한다.
- 문서 개편은 승인된 ADR을 대신하지 않는다. baseline 감사는 보존하고 F13 workflow와 F21 source 재현 문제를 열린 상태로 남겨, 예상점수가 실제 운영 점수처럼 읽히지 않게 하자.

## 종합

**논제:** 단순한 배포 구조를 유지하면서 감사 공백과 지도/검색 정책을 구체적 명세로 연결한다.

**남은 가장 강한 찬성 근거:** 오류/소유권/순서/만료 정책을 미리 연결하면 FE와 BE가 서로 다른 정상 상태를 구현하는 위험을 줄인다.

**남은 가장 강한 반대 근거:** 세부 숫자와 설계 문서가 늘어도 호스팅·실제 데이터·계약 테스트가 없으면 검증되지 않은 가정은 남는다.

**핵심 쟁점:** 작은 자원에서 실제 사용자 흐름을 지키는 데 필요한 정책과, 나중에 검증할 최적화의 경계를 어디에 둘 것인가.

**결론을 바꿀 증거:** warm/cold 부하, 한국어 retrieval 평가, actor/삭제 negative test, kill/late-worker 실험, 복원drill 결과. 상한 내에서 실패하면 scope/설정을 축소하거나 배포 자원을 다시 결정한다.

검토 제안은 책임별 설계 문서에 반영했다. 구조 선택에 대한 정식 판단은 [ADR 구조 초안](../../decisions/drafts/foundation-architecture.md)을 검토한 사용자에게 남아 있다.

## 사용자 정정 반영

사용자는 지도 글이 기존 온기 mood/score가 아니라 한옥·한옥 숙박·한옥 카페·전통시장 방문 후 짧은 후기이며 좋아요와 대댓글 없음이라고 명확히 했다. 이에 VisitReview/ReviewLike를 분리했다. 길이300자/5줄과 댓글 전체 제외는 제안값이다. 좋아요는 인기순 자동가중 대신 멱등 PUT/DELETE·DB 유일제약부터 정의했다.
