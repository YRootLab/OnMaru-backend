# Backend Issue Graph Completion Design

## 목적

2026-09-13에 발행한 OnMaru Backend Issue Graph를 구현 직전 다시 감사한 결과, 그래프 형식은 정상이나 일부 요구사항이 독립 구현 결과물 또는 올바른 선행 관계로 표현되지 않았다. 이 변경은 기존 Root·Track 구조를 유지하면서 누락 Leaf와 dependency만 보강하여 여러 Agent가 서로 다른 계약을 구현하거나 완료 게이트를 조기에 통과하는 일을 막는다.

## 검토한 접근

### 접근 A — 기존 Issue의 Scope만 확대

Issue 수는 늘지 않지만 하나의 PR에서 계약, migration, provider 검증, API, 운영 drill을 함께 처리하게 된다. 독립 검증과 병렬 실행이 어려워지고 기존 Issue 제목만으로 실제 범위를 알기 어렵다.

### 접근 B — 독립 산출물만 새 Leaf로 추가

계약, schema, 원천 데이터, 공개 API, 운영 gate처럼 별도 PR과 pass/fail 기준을 가진 결과물만 분리한다. 기존 구현 Issue에는 누락된 dependency와 acceptance criteria만 추가한다. 이 방식을 채택한다.

### 접근 C — endpoint와 fixture마다 세분화

병렬성은 높아 보이지만 의미 있는 단독 PR이 되지 않는 XS 작업이 늘고 Root 추적 비용이 커진다. 계약 묶음과 통합 gate를 release 경계별로 유지하는 편이 적절하다.

## 추가할 Leaf

| ID | Priority | 결과물 | 직접 선행 조건 |
|---|---:|---|---|
| F07 | P0 | backend 입력 문서의 repository-local snapshot·provenance manifest | 없음 |
| F08 | P0 | 공개 command 공통 rate-limit·IP/member admission | F06, D07 |
| F09 | P0 | 인증·회원·SavedResource 기계 판독 OpenAPI·fixture | F07 |
| D09 | P0 | `insights` schema 실행 Flyway migration | D01, D02 |
| P07 | P1 | 행정구역 경계 자료 출처·권리 검증과 revision import | D02, D07, F07 |
| M06 | P1 | 지도·Odii·관광 관측 R2 OpenAPI·fixture | F07, C01 |
| M07 | P1 | 방문자·관광지 집중률 공개 조회 API | D09, P06, M06 |
| M08 | P1 | 보호된 moderation queue·operator drill·runbook | M05, O02 |
| M09 | P1 | R2 지도·후기·Odii·찜 통합 계약/E2E gate | M01, M04, M07, M08, A03, A04, I05, I06, F05 |
| J11 | P2 | Journey actions·SavedJourney를 포함한 완전한 OpenAPI·fixture | F07, F09 |

F09는 인증과 개인 저장 API가 같은 session·CSRF·principal 계약을 공유하므로 한 Issue로 유지한다. M06은 R2의 지도·Odii·관광 관측 소비 계약을 한 release contract bundle로 검증한다. VisitReview OpenAPI는 기존 파일이 있으므로 M06에서 runtime drift만 함께 확인하고 다시 작성하지 않는다.

## 기존 Issue 보강

- C06은 R1에서 장소 찜까지 검증하도록 I05를 dependency에 추가한다.
- M01은 검증된 행정경계 없이는 좌표 resolve를 활성화하지 않도록 P07을 dependency에 추가한다.
- P06은 관측 저장 schema와 지역 revision을 전제로 D09와 P07에 의존한다.
- D08은 insights migration까지 전체 migration matrix에 포함하도록 D09에 의존한다.
- I01, M03, M05, J01은 공개 command admission이 준비된 뒤 구현하도록 F08에 의존한다.
- I01·I04·I05·I06·I07은 F09의 인증·개인 저장 계약을 구현 기준으로 사용한다.
- A03, M01, M07은 M06 R2 계약을 구현 기준으로 사용한다. M06 자체가 구현 Issue에 의존하지 않도록 계약이 선행한다.
- J01, J06, J08은 J11의 Journey 계약에 의존한다. J11은 구현 Issue에 의존하지 않는다.
- O09는 개별 R2 Leaf 대신 M09 통합 gate 결과를 반드시 포함한다.
- D01과 O07의 acceptance criteria에 migration/runtime/readonly/backup role 권한 분리와 runtime DDL 금지를 추가한다.

## 문서 거버넌스 정리

- 사용자가 승인한 ADR-0003~0009의 상태를 `accepted`로 전환한다.
- `catalog-ingestion.md`의 지도 1.2를 더 이상 단순 제안으로 표시하지 않고, `rest-api.md`와 동일한 구현 전 계약으로 정렬한다.
- `rest-api.md`의 “OpenAPI 동결 전 구현 Issue 발행 금지”는 “관련 기능 구현 착수 금지”로 명확히 한다. Scaffold·계약 Issue 발행 자체는 허용한다.
- #49는 이 변경의 PR이 `develop`에 merge되고 acceptance criteria가 확인될 때까지 Open으로 유지한다.

## 병렬 실행과 충돌 방지

F07은 문서 입력만, D09는 insights migration만, P07은 경계 importer만 소유한다. F09·M06·J11은 서로 다른 OpenAPI 파일을 소유한다. M07과 M08은 각각 insights public query와 operations moderation 경계를 소유하므로 병렬 가능하다. 공통 문서 인덱스와 Root/Track 본문은 graph renderer/publisher가 단일 순차 단계에서 갱신한다.

## 검증 기준

- Work Graph validator에서 중복 ID, 누락 dependency, cycle, 필수 필드 오류가 0이다.
- 같은 Wave의 touch point 충돌 후보는 dependency 또는 `parallel_notes`로 해소한다.
- 요구사항 매핑에서 BE-REQ-005가 D09·P06·M07로, BE-REQ-010이 F09·I05~I07·C06·M09로 추적된다.
- GitHub Root 아래 신규 Leaf가 올바른 Track Sub-Issue로 연결된다.
- GitHub native blocked-by/Blocking 관계가 로컬 graph와 일치한다.
- 기존 #52~#130의 본문, dependency, Wave가 갱신된 graph와 일치한다.
- ADR 상태와 지도 1.2 계약 문구가 서로 모순되지 않는다.

## 범위 밖

- Spring/FastAPI runtime 구현
- 실제 OpenAPI schema 작성과 provider live 호출
- FE 컴포넌트 또는 `docs/toFE/**` 수정
- GitHub Issue 완료 처리 또는 #49 조기 종료
