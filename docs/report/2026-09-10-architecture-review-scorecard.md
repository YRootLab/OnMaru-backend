# 아키텍처 감사 채점 및 검증 부록

[주 보고서](2026-09-10-architecture-review.md)의 점수는 판단을 수치화한 것이며 통계적 측정이나 운영 인증이 아니다. 동일 근거를 보고 다른 검토자가 재채점할 수 있도록 배점과 이유를 공개한다.

## 사용자 지정 점수

각 영역을 네 질문으로 나누었다. `요구/책임이 명시됐는가`, `다른 문서와 일치하는가`, `실행 가능한 정책인가`, `합격 여부를 검증할 수 있는가` 순서다. 아래 숫자는 각 질문의 획득/배점이며 영역 합계는 주 보고서와 일치한다. 실측이 아닌 설계 수준의 검증 가능성이다.

| 영역 | 명시성 | 일관성 | 실행 가능성 | 검증 가능성 | 합계 | 근거 |
|---|---|---|---|---|---:|---|
| Requirements | 2/2 | 1/3 | 1/2 | 2/3 | 6/10 | F01-F04, MVP 우선순위 |
| Domain | 3/3 | 2/3 | 1/2 | 2/2 | 8/10 | Spring 소유권, F04/F16 |
| API | 2/2 | 1/3 | 1/3 | 1/2 | 5/10 | typed DTO, F01-F03/F15/F17 |
| Data | 3/3 | 2/3 | 1/3 | 1/3 | 7/12 | canonical/index, F04/F06/F07 |
| Reliability | 3/3 | 1/3 | 1/3 | 1/3 | 6/12 | LKG/timeout, F05-F08 |
| Security | 2/2 | 1/2 | 1/3 | 1/3 | 5/10 | cookie/CSRF, F09-F11 |
| Performance | 2/2 | 1/2 | 1/3 | 1/3 | 5/10 | 공간 검색 상한, F10 |
| Observability | 2/2 | 1/2 | 1/2 | 1/2 | 5/8 | 지표 목록, F18 |
| Deployment | 1/2 | 0/1 | 1/2 | 1/2 | 3/7 | 환경 격리, F12-F14 |
| Testing | 1/1 | 1/1 | 1/2 | 1/2 | 4/6 | Testcontainers/WireMock 전략, 최신 failure fixture 공백 |
| Cost | 1/1 | 0/1 | 2/2 | 1/1 | 4/5 | broker/vector 제외, F12 |

점수의 재현은 산술의 재현이다. 각 획득 점수는 evidence에 기반한 검토자의 판단이며 객관적 실측치라는 의미가 아니다. 하나의 finding을 여러 관점에서 참조할 수 있지만 별도 정액 감점을 중복 적용하지 않았다.

## 스킬 추가 기준

production-readiness-reviewer의 10개 카테고리와 세부 항목을 빠뜨리지 않기 위해 원래 rubric 순서를 유지해 아래 설계 평가를 작성했다. 원래 rubric은 구현·실측을 요구하므로 **설계 적응형 보조 점수**로만 계산한다. 실행 증거가 없는 항목을 VERIFIED로 승격하지 않는다. runtime 상태는 전부 UNKNOWN이며, workflow에 한해서 설정을 직접 확인했다.

각 배열은 스킬 `scoring-rubric.md`의 해당 카테고리 세부 항목 순서다. 모든 항목을 적용하며 N/A 재분배는 없다. 불필요한 enterprise 제품 부재에 대한 penalty는 0이다. 0점은 설계에서 실행 근거까지 연결하지 못했다는 뜻이지 실제 장애가 확인됐다는 뜻이 아니다.

| Category | 획득 배열 | 배점 배열 | 주요 근거 |
|---|---|---|---|
| reliability | 12,12,10,2,7,3,4 | 20,20,15,10,15,10,10 | F05-F08/F10, board 보존 |
| observability | 12,14,8,6,4,5 | 20,20,15,15,15,15 | F18, PRD SLO 초안 |
| security | 8,9,10,2,3,3,7,3 | 15,15,15,15,10,10,10,10 | F09-F11/F20, 내부 credential 경계 |
| performance | 10,4,2,9,4,6,4 | 25,15,15,15,10,10,10 | 부하 목표·공간 index, 용량 예산 미정 |
| scalability | 10,9,6,5,8,7,3 | 20,15,15,10,15,15,10 | DB 상태 소유, F05/F07/F10 |
| operability | 7,10,9,4,5,7,6 | 20,15,15,15,15,10,10 | F12-F14, health 분리·expand/contract |
| recoverability | 5,0,4,3,10 | 20,25,20,15,20 | RPO/RTO 목표는 존재, F14 |
| maintainability | 21,11,0,6,10,7,2 | 25,15,15,10,15,10,10 | modular monolith, F04/F21, 코드 미구현 |
| testability | 9,10,9,12,4,7 | 15,15,15,25,15,15 | 테스트 전략 및 본 보고서 failure matrix |
| architecture | 17,16,8,9,8,13 | 20,20,15,15,10,20 | Spring/FastAPI 경계, F03-F08 |

위 배열을 원래 항목 ID에 매핑한 assessment JSON을 외부 scratch에 만들고, 제공된 `scripts/score_calculator.py`로 계산한다. raw tier는 도구의 일반적인 명칭이므로 그 결과를 OnMaru의 실제 production readiness로 해석하면 안 된다. 주 점수 58점과 보조 점수는 가중치·항목이 달라 합치거나 평균내지 않는다.

### 계산 결과

2026-09-11 확인: 사용자 배점 합계 100, 획득 합계 **58**. 스킬 calculator의 설계 적응형 보조 결과는 **47.5/100**이다. 카테고리 순서대로 50, 49, 45, 39, 48, 48, 22, 57, 51, 71점이다. 도구의 raw/final tier 문자열은 `Development Ready`이며 performance 39와 recoverability 22가 기본 floor 40 미만으로 기록됐다. 이는 실측 성능 실패가 아니라 설계·검증 근거 부족을 반영한다.

자동 scanner의 `evidence.json`, 위 표에서 생성한 `assessment.json`, 도구 출력 `scores.json`은 `/tmp/onmaru-audit.Gn2gBO`에 보관했다. 임시 경로는 영구 산출물이 아니므로 재채점에 필요한 배점·획득 배열과 해석은 본 문서에 모두 남겼다. scanner의 링크 skip/truncation 때문에 scanner 단독으로 부재를 확정하지 않았다.

사용한 계산기: `/Users/yangseunghyeon/Development/Agent-toolkit/skills/develop/production-readiness-reviewer/scripts/score_calculator.py`.

```sh
python3 /Users/yangseunghyeon/Development/Agent-toolkit/skills/develop/production-readiness-reviewer/scripts/score_calculator.py \
  --assessment /tmp/onmaru-audit.Gn2gBO/assessment.json \
  --out /tmp/onmaru-audit.Gn2gBO/scores.json
```

## 여덟 단계 수행 기록

| Pass | 수행 | 산출/한계 |
|---|---|---|
| 1 Discovery | tracked docs/workflow, linked FE 계약, scanner | 절대 symlink 및 scanner skip 명시 |
| 2 Architecture Model | 책임·신뢰 경계 재구성 | 주 보고서 Mermaid |
| 3 Consistency Audit | API/DB/PRD/환경/roadmap 비교 | cross-document matrix 및 F01-F21 |
| 4 Quality Attributes | 사용자 11축 + 스킬 10축 | 두 평가를 구분 |
| 5 Failure Analysis | DB/API/AI/queue/동시성/배포/복구 | 주 보고서 failure table |
| 6 Scoring | evidence 판단 후 산술 계산 | 본 부록과 calculator |
| 7 Findings | 독립 리뷰 결과 중복 제거 | P0 0, P1 14, P2 7, P3 0 |
| 8 Remediation | 선행 관계·acceptance test | 수정하지 않고 순서만 제안 |

## 관측성 최소 검증 세트

- correlation: HTTP requestId, W3C trace context, runId의 의미를 분리하고 executor 전파를 시험한다.
- errors: 사용자 응답은 안정된 code·correlation ID, 내부 로그는 exception chain·layer·operation을 남긴다. 원문 query/쿠키/원천 service key는 남기지 않는다.
- metrics: request rate/error/latency, pool wait, executor queue/reject, run age/outcome, sync age/quarantine, backup age를 수집한다.
- alerts: 임계치뿐 아니라 수신자·확인 방법·대응 runbook과 실제 발송 테스트를 포함한다.
- cardinality: query, placeId, userId를 metric label로 무제한 넣지 않는다. sampling과 보존 예산을 정한다.
- 운영 제품은 수단이다. 무료 외부 관측 서비스 또는 stdout 기반 시작을 허용하며 자체 Zipkin/Loki 클러스터를 요구하지 않는다.

## 재검토에 필요한 미확인 입력

실제 호스팅 리소스/idle 정책, canonical 파일럿 자료의 사용 권한·좌표 품질, provider quota, 실제 DB connection limit, OAuth 등록 환경, 백업 제공 기능, 배포 담당자·알림 수신자, GitHub protection 상태는 UNKNOWN이다. 모델을 도입한다면 대표 한국어 질의와 근거 정확도 평가셋을 먼저 고정해야 한다. 이 입력 없이 비용·처리량·AI 성능을 확정하지 않는다.
