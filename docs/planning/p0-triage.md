# P0 정리와 결정 검토

2026-09-09. 미완료 입력은 완료로 바꾸지 않는다. GitHub 발행 전 임시 triage이며 발행 후 Issue 번호로 교체한다.

## 현재 운영 상태

PR #48은 develop에 병합됐다. CI verify 성공, CodeRabbit 성공, gemini-review 실패. 실패 job annotation 조회는 HTTP 404여서 원인은 확인하지 못했다. PR 본문에 관련 Issue 연결이 없고 reviewDecision이 비어 있어 승인 정책 충족을 소급 주장하지 않는다. 후속 PR은 관련 Issue를 연결한다.

## P0 분류

| 항목 | 처리 | 후보 / 남은 증거 |
|---|---|---|
| 참가 범위/마감/인원/예산/데모 | 입력 대기 유지 | W0; planning README 입력표 확정 |
| 두 참고 자료 | 입력 대기 유지 | W0; URL/경로 수령 후 영향 검토, 임의 제외 금지 |
| 재현 가능한 참고 자료 | 부분 조사, 미완료 유지 | W0; 아래 hash와 dirty 상태, 전체 snapshot은 아직 없음 |
| FE key fallback | FE 보안 작업으로 연결 준비 | W0 blocker; 노출 평가, 제거, 필요 회전과 폐기 검증. 실제 키·키 hash 기록 금지 |
| canonical ID/provenance | 정책 제안 존재, 승인/fixture 대기 | W0/D3 및 X0; W2 migration 전 확정 |
| W/X graph 통합 | 로컬 산출물 완료 | work-graph.json 및 implementation-issues.md; 21 후보, wave 0~9 |

## 참고 자료 재현성

FE upstream HEAD: `3a08c2a5069a762d25ad1f5e0c78e6f659ebbbfa`. docs/specs 범위는 git status상 변경 없음.
외부 문서 upstream HEAD: `adbab72c1ee121554d914117b262929d94a3cd99`. backend_schema_design_guide.md는 수정 상태이므로 이 commit만 checkout하면 현재 입력과 같지 않다.

| 입력 | 현재 파일 SHA-256 |
|---|---|
| docs/specs/traceability/fe-be-traceability-matrix.md | 4d3bffd86c0e501335f46cd075731eda354cbe50add6a08b04ef84054c133f1d |
| docs/specs/data-contracts/place-canonical.md | 3f44d41fb866e66a4ac683898e18bd5ef1b3062dfca0be01c8c85128d125f87b |
| docs/backend_schema_design_guide.md | dfdd50ae905da6610f2b8d58c1a19c21f111510249313264dea53487a33a3766 |

이는 세 입력의 metadata이며 전체 specs/source snapshot이 아니다. W0에서 비밀정보 검토 후 필요한 문서/fixture allowlist만 저장소 내부 snapshot으로 고정하고 원본 경로·commit·dirty diff 여부·hash를 manifest에 기록한다. 원본 symlink는 수정하지 않는다. 계약은 BE contracts 소유, FE decoder/fixture는 FE 소유, 계약 변경 시 양측 버전과 검증을 갱신한다.

## 보안 완료 조건

FE 유지보수자가 fallback 제거와 server-only secret loading을 검증하고 노출 범위·회전 필요성을 판단한다. 회전한 경우 이전 키 폐기 및 배포된 설정 사용 여부까지 확인한다. 이 저장소에서는 키 값을 읽어 출력하거나 외부 FE 파일을 변경하지 않았다. 제거·회전이 완료됐다고 보고하지 않는다.

## ADR 검토

| 후보 | 검토 결과 | 확정 전 검증 |
|---|---|---|
| 런타임 역할 | 사용자 선택을 ADR-0002로 기록 | W1/W8 각각 build와 계약 검증 |
| D1 | 순수 core/선택 Hexagonal/Gradle은 제안 유지 | 작은 모듈 경계와 팀 관리 비용, forbidden dependency fixture |
| D2 | PostgreSQL/PostGIS와 AI schema는 제안 유지 | hosting/extension 지원, 권한 분리, restore/부하 |
| D3 | UUID + namespace composite key 권고, 미확정 | 동명 장소/언어별 ID/legacy fixture와 출처·날짜·공간 단위 |
| D4 | Spring 검증 근거 + 내부 AI 계약 권고 | 기존 question+filters 호환, timeout/usage/revision; E5와 중복 소유권 통합 |
| E1 | typed block/FE layout 경계 제안 | registry 버전 호환 및 unknown block 처리 |
| E2 | 관계형 관계 검색 제안 | D2 승인 후 SQL baseline, graph DB 필요성 실측 |
| E3 | durable run/SSE/snapshot 제안 | actor isolation, lease, event TTL, replay와 cancel race; W9 request 취소와 구분 |
| E4 | UGC/지역 관측/편집/행동 의미 분리 권고 | D3의 provenance와 연결, 표본 미달 인기 노출 차단 |
| E5 | 공개 projection + canonical 재검증 권고 | D4 호출 경계와 한 번만 정의; DB 접근 권한/삭제·revision 지연 검증 |

새 승인 없는 구조 선택을 Accepted로 승격하지 않는다. 모델명은 평가 기록, DB/호스팅/권한을 바꾸는 결정만 추가 ADR 후보로 취급한다. 현재 공식 결정은 ADR-0001 과정과 ADR-0002 사용자 선택뿐이다.
