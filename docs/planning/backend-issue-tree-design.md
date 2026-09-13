# Backend 구현 Issue Tree 설계

2026-09-13. `docs/toFE/**`의 FE 구현 작업은 제외하고, OnMaru backend 문서에 확정된 Spring Boot, FastAPI, PostgreSQL/PostGIS, 외부 데이터 수집, 공개 계약, 운영 요구를 GitHub Issue Graph로 옮기는 기준이다.

## 목표

Claude, GPT, Agy 등 서로 다른 Agent 세션이 전체 문서를 다시 분석하지 않고도 하나의 최하위 Issue만 읽고 독립적으로 구현·검증할 수 있어야 한다. 동시에 시작할 수 있는 작업과 선행 작업을 기다려야 하는 작업을 GitHub native Sub-Issue와 blocked-by 관계로 판별할 수 있어야 한다.

## 트리 구조

깊이 2의 관리 트리를 사용한다.

```text
Mega Root: OnMaru Backend 전체 구현
├── Track A: 기반·계약·개발환경
├── Track B: 데이터베이스·관광공사 수집
├── Track C: 한옥·장소 공개 API
├── Track D: 인증·개인 저장
├── Track E: 후기·지도·Odii
├── Track F: FastAPI AI 서버
├── Track G: 여정 실행
└── Track H: 운영·출시
```

Mega Root와 Track은 control plane이다. 구현 코드는 최하위 Leaf Issue에서만 만든다. Leaf 하나는 원칙적으로 한 Agent 세션, 한 worktree, 한 PR, 독립적인 검증 결과 하나에 대응한다. 전체 규모는 Track 8개와 Leaf 약 55~65개를 목표 눈금으로 사용하되, 문서 요구사항과 검증 경계에 따라 최종 개수를 조정한다.

## 실행 상태의 판정

별도 커스텀 상태 시스템을 만들지 않는다. 각 세션은 GitHub 상태를 다음처럼 해석한다.

| 상태 | 판정 규칙 | 세션 행동 |
|---|---|---|
| `Ready` | Issue가 Open이고 모든 blocked-by Issue가 Closed이며 담당자와 열린 PR이 없음 | 새 세션이 할당받아 착수할 수 있다. |
| `Waiting` | blocked-by 중 하나 이상이 Open | 구현하지 않고 선행 Issue의 merge·close를 기다린다. |
| `In progress` | 담당자가 지정되었거나 해당 Issue를 참조하는 열린 PR이 있음 | 다른 세션은 같은 Issue를 중복 구현하지 않는다. |
| `Review` | 구현 PR이 열렸고 required CI 또는 review가 남음 | 새 구현보다 review와 수정에 집중한다. |
| `Done` | Acceptance Criteria와 merge 상태 확인 후 Issue가 Closed | 후행 Issue가 자동으로 Ready 후보가 된다. |

선행 Issue가 단지 관련 있다는 이유로 dependency를 만들지 않는다. 실제 산출물이 없으면 후행 작업을 시작할 수 없는 경우에만 blocked-by를 연결한다.

## 병렬 세션 규칙

- 같은 Wave라도 `expected_touch_points`가 겹치면 동시에 실행하지 않는다.
- Gradle settings, 공통 Spring application 설정, 전역 Flyway version, 공통 OpenAPI 문서는 소유 Issue를 하나만 둔다.
- 다른 Track의 Leaf라도 touch point가 다르고 blocker가 닫혔다면 병렬 실행할 수 있다.
- 각 Agent는 Issue의 `Dependencies`, `Blocks`, 로컬 Mermaid graph, touch points, conflict notes를 확인한 뒤 작업한다.
- 작업 중 새 dependency나 공통 파일 충돌을 발견하면 구현을 억지로 계속하지 않고 source work graph와 GitHub 관계를 먼저 갱신한다.
- Agent 종류는 Issue에 고정하지 않는다. Java/Spring, Python/FastAPI, PostgreSQL, 운영처럼 필요한 역량만 label과 본문에 기록한다.

## Branch와 완료 규칙

- 작업 branch는 `feature/issue-<number>-<slug>`를 기본으로 하고 `develop` 대상 PR을 만든다.
- PR 본문은 관련 Leaf Issue를 참조한다. merge가 곧 완료인 경우에만 auto-close keyword를 사용한다.
- 코드가 없어도 독립 검증 산출물이 있는 외부 API qualification, backup/restore drill, 부하·장애 시험은 별도 Leaf가 될 수 있다.
- Track Issue는 하위 Leaf를 모두 닫고 Track 통합 게이트를 통과한 뒤 닫는다.
- Mega Root는 채택된 모든 Track과 최종 release gate가 닫힌 뒤 수동으로 닫는다.

## 범위 기준

포함 범위는 BE-REQ-001~010, Spring modular monolith, FastAPI AI 서비스, PostgreSQL/PostGIS schema와 migration, 한국관광공사 국문·Odii·관광 관측 데이터 adapter, 인증·찜·월간 타임라인, VisitReview, 여정 REST/SSE/snapshot, optional RAG 평가, 관측성·보안·복구·릴리스다.

FE 컴포넌트 구현과 `docs/toFE/**` 자체의 작업은 제외한다. 다만 backend가 제공할 OpenAPI, JSON fixture, serializer contract test, canonical ID와 FE decoder 호환성은 backend 완료 조건에 포함한다. 예약·결제·소셜 관계·현장 체크인·개인화 추천·정-길·관리자 UI는 현재 Out of Scope로 남긴다.

## Work Graph 산출물

다음 단계에서 기존의 오래된 `docs/planning/work-graph.json`과 `implementation-issues.md`를 최신 계약 기준으로 교체한다. 각 Leaf에는 Objective, Context, Scope, Out of Scope, Implementation Notes, 관련 경로, blocked-by, blocks, Mermaid local graph, expected touch points, 병렬 충돌 메모, Acceptance Criteria, Verification Method를 포함한다.

Graph는 GitHub 생성 전에 `validate_work_graph.py`와 `compute_waves.py`로 검증한다. 전체 제목·의존성·Wave·중복 검토 결과를 사용자에게 보여주고 승인받은 뒤에만 실제 GitHub Issue를 생성한다.
