# 감사 기준선을 유지하면서 설계 개선과 남은 검증을 추적한다

2026-09-11. [기준 감사](2026-09-10-architecture-review.md)와 [배점 부록](2026-09-10-architecture-review-scorecard.md)의 **58/100**은 당시 설계 완결성 평가로 보존한다. 본 문서는 새 독립 감사가 아니라 개선 작성자의 추적 및 예상 재평가다. 기존 P1/P2를 runtime 해결 완료로 닫지 않는다.

## 이번에 달라진 점

[개선 문서 인덱스](../planning/revision-2026-09-11/README.md)에 최신 정책을 묶고 기존 Blueprint, data API, 7일 FE 전달서, 환경설정을 실제 수정했다. 핵심 변경은 런타임/컴파일 그림 분리, 소비자 port와 bridge DAG, identity/discovery/journey/VisitReview 책임, 환경당 DB1개, 검색rank30→전달12→표시3, 선택RAG 평가, 지도범위/cursor/좋아요, 인증grant/저장재개, run 만료와 LKG 원자게시다.

사용자 정정에 따라 지도 게시글은 온기와 별도의 방문 짧은 후기다. 좋아요·대댓글 없음은 사용자 요구이며 300자/5줄·댓글 전체 제외는 제안값이다. 방문 인증을 수행하지 않으므로 실제 방문 여부를 보증하지 않는다. 지도 전체/지역/인근/viewport 상세 설계는 준비했지만 여정 중심 P0에 자동 포함하지 않는다.

## F01–F21 대응표

상태 `설계 보완`은 정책과 검증 조건이 문서에 연결되었다는 뜻이다. `실행 변경 남음`은 설정/원천/운영 실체가 아직 바뀌지 않은 항목이다. 모든 항목의 구현 검증은 미완료다.

| ID | 대응 정책 / 근거 | 설계 상태 | 닫기 위한 실제 증거 |
|---|---|---|---|
| F01 | [API](../planning/revision-2026-09-11/api-contract.md) typed clarification+answer | 설계 보완 | FE 질문렌더→같은탐색 후속run contract |
| F02 | API EXCLUDE/UNEXCLUDE, pin충돌/version | 설계 보완 | 새로고침/재탐색에도 제외 유지 |
| F03 | [검색](../planning/revision-2026-09-11/retrieval.md) 지역선해석+모호함 질문 | 설계 보완 | 동명이인 지역/무지역/region불일치 fixture |
| F04 | [데이터](../planning/revision-2026-09-11/data-and-identity.md) identity/session/saved/FK/unique | 설계 보완 | 실제migration+동시login/save 검증 |
| F05 | [실행](../planning/revision-2026-09-11/runtime-and-operations.md) QUEUED2초/RUNNING20초 만료, GET/startup/sweeper | 설계 보완 | commit-dispatch 사이kill 후run해제 |
| F06 | 실행 dataset revision+active pointer transaction | 설계 보완 | 마지막page실패 시 공개revision 불변 |
| F07 | 실행 owner/generation/DBclock lease/fence | 설계 보완 | A만료→B게시→A결과에서A쓰기0 |
| F08 | 실행 remaining budget/retry0/terminal CAS | 설계 보완 | 취소/완료/만료경합 terminal 하나 |
| F09 | 데이터 state-browser-guest binding/단기grant/저장재검증 | 설계 보완 | 타인ID/재사용callback/만료grant 차단 |
| F10 | 실행 actor/IP/global/byte/DB/worker 예산 | 설계 보완 | 신규exploration폭주에서 상한 준수 |
| F11 | 데이터 TTL/DELETING/late-result/backup deletion ledger | 설계 보완 | 만료·탈퇴·복원 후 재노출 없음 |
| F12 | 환경 dataMode와 ENGINE 분리, BASELINE 운영허용 | 설계 보완 | provider없는 LIVE_CANONICAL 기동/표시 |
| F13 | 실행 동일main SHA verify→release dependency 명세 | 실행 변경 남음 | workflow 변경+실패SHA tag/release0 |
| F14 | 실행 backup/RPO/RTO/restore순서 | 운영 입력 남음 | 업체/보관소/담당자 확정+restore drill |
| F15 | API/internal orderedRefs complete set | 설계 보완 | A-C유지/B추가→A-B-C와leg 일치 |
| F16 | API pin/exclusion version+pending무효화 | 설계 보완 | 오래된apply가pin을 지우지 않음 |
| F17 | API saved→새exploration resume+unavailableRefs | 설계 보완 | 새기기에서저장열기→편집→재저장 |
| F18 | 실행 trace전파/allowlist/경보 threshold/runbook | 운영 입력 남음 | 실제수신자 설정/시험알림/trace조회 |
| F19 | 실행 stale dataset별정책/overlap/hash/tombstone | 설계 보완 | 순서변경/동일timestamp/부분page 검사 |
| F20 | API/search Unicode/plaintext/allowlist/no tools/UGC분리 | 설계 보완 | HTML/injection/정상지명 오탐 fixture |
| F21 | 실행 pinned snapshot+manifest 전환 설계 | 실행 변경 남음 | 외부symlink 대체 및 빈clone hash검증 |

## 다음 설계 재평가의 예상 점수

동일 11개 영역/100점 배점을 적용하면 **79/100을 예상**한다. 평가자의 문서 검토에 따른 예측이며 현재의 공식 재감사 점수·운영 준비도·성능 개선율이 아니다. 새 문서들이 구현 기준으로 승인되고 FE 계약 통합이 유지된다는 전제다. 승인/구현이 없어도 실제 시스템 보안이 향상되었다고 계산하지 않는다.

| 영역 | 배점 | 기준선 | 예상 | 변화 근거 / 남은 감점 |
|---|---:|---:|---:|---|
| Requirements & Traceability | 10 | 6 | 8 | F01/02/17과VR추적; 아직 FE 원본/Issue graph 통합 안 됨 |
| Domain Architecture | 10 | 8 | 9 | 최신aggregate/port/DAG/lock 책임; ArchUnit/runtime cycle 미검증 |
| API Design | 10 | 5 | 8 | 지도/좋아요OpenAPI와여정prose계약; 전체여정OpenAPI/consumer test 남음 |
| Data Architecture | 12 | 7 | 10 | 회원/저장/후기/lease/revision제약; executableDDL 없음 |
| Reliability | 12 | 6 | 9 | queue/deadline/fence/LKG경계; kill/late결과 실험 없음 |
| Security | 10 | 5 | 8 | state/grant/CSRF/소유/삭제/admission; negative test 없음 |
| Performance & Scalability | 10 | 5 | 7 | pool/thread/bytes/rank/viewport 상한; hosting/EXPLAIN 미측정 |
| Observability | 8 | 5 | 6 | trace/log/threshold/runbook; 수신자/실제발송 미정 |
| Deployment & Operations | 7 | 3 | 4 | restore/release설계; F13실제workflow·F14drill 남음 |
| Testing | 6 | 4 | 5 | failure/FE/rank acceptance 구체화; runtime fixture실행 없음 |
| Cost & Complexity | 5 | 4 | 5 | 모델선택/DB1/Redis·SSE보류/예산gate 일관성 |
| 합계 | 100 | 58 | 79 | 예상 +21점; 실제재평가 아님 |

소수점 정밀성을 주장하지 않는다. 두 평가자가 항목별로 1점씩 다르게 판단할 수 있으며 정확히79점을 보장하지 않는다. 90점 이상을 예측하지 않은 이유는 schema/prose만으로 계약·배포·복원 증거를 대체할 수 없기 때문이다. 기존 보조점수47.5/100은 rubric이 달라 재산출하거나 이 점수와 평균내지 않았다.

## 검증 범위와 다음 순서

이번 검증은 repository hygiene, 새 Markdown 상대 링크, JSON/OpenAPI 구조, 방문후기 JSON Schema의 정상·길이/개행 제한 사례, ADR preflight/related/significance/기존ADR validate/check다. backend build/DB/FE runtime이 없어 통합·성능·보안·복원 실험은 실행하지 않았다. 구체적 결과는 handoff의 최신 검증 기록을 따른다.

1. 사용자에게 [ADR 초안5개](../planning/revision-2026-09-11/adr-review.md)를 제시하고 승인 후 toolkit으로 proposed 등록한다.
2. FE와 방문후기 범위/여정타입1.1을 동결하고 전체OpenAPI/fixture를 작성한다.
3. GitHub Issue로 수용조건을 발행하고 W/JX/VR graph를 일치시킨다. 로컬 문서를 영구backlog로 사용하지 않는다.
4. 구현 후 F01–F21 acceptance와 hosting/backup gate를 실제검증하고 그때 독립 재감사를 수행한다.

원본 감사 두 파일은 보존했다. 코드/scaffold/workflow/외부 FE symlink/기존 accepted ADR을 변경하지 않았으며 commit/PR/merge는 수행하지 않았다.
