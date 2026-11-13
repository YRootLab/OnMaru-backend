# 여정 RAG 평가 연동 — 백엔드 구현 및 검증 계획

- 상태: 계획 (구현 일부 진행, 운영 전송 없음)
- 관련 이슈: #811
- 기준 AI 이미지: 공식 v0.1.3 `sha256:93009d02…` (tag `v0.1.3`, commit `fc93536`)
- 역할: 골드 데이터셋, 기준선 정의·실행, RAG 실행, 비교 판정은 AI팀 담당. 백엔드는 기준선을 만들지 않는다.

## 0. 원칙

1. **역순 배포 금지.** AI 요청 모델 `JourneyProposalRequest`는 v0.1.3에서 `extra="forbid"`다. AI 수용 버전 배포와 contract smoke 확인 전에는 백엔드 요청에 `candidatePolicy`를 넣지 않는다.
2. **RAG 전송 금지.** `useRag=true`는 AI 영속 activation log, retriever, hybrid coordinator 연결과 retrieval smoke, 평가 승인이 끝나기 전까지 보내지 않는다. `ONMARU_RAG_ENABLED`는 OFF로 유지한다.
3. **서버 플래그가 최종 통제값.** 요청 플래그만으로 서버 차단을 우회하지 않는다.
4. **모듈 포함과 실행을 구분.** 이미지에 파일이 있다는 사실을 운영 검색 실행 증거로 쓰지 않는다.

## 1. candidatePolicy=v1 계약

### 1.1 규칙 (v1)
- 후보는 **전국 ACTIVE 장소**에서 만든다. 지역은 후보 제외 조건이 아니다.
- 질의 단어(조사·불용어 제외, 최대 6개)를 장소 이름·주소·카테고리와 대조해 점수를 매긴다.
  - 이름 일치 +3, 카테고리 일치 +2, 주소 일치 +1
  - 한옥·고택 질의는 `HANOK*` 카테고리에 +3
- 지역 코드가 있으면 같은 시도/시군구 접두사를 가진 장소에 +2 (가산점). 제외하지 않는다.
- 정렬: 점수 내림차순 → 이름 오름차순 → 장소 ID 오름차순.
- 상한 **12개**.
- 질의 단어가 하나도 없으면 점수는 지역 가산점만으로 정한다.
- 구현 위치: `JourneyRelevanceRanker` (점수 규칙), `ApprovedPlaceReader.readPool` (전국 풀). 규칙 상수는 한 곳에 모으고 버전 문서에 같은 값으로 적는다.

### 1.2 요청 필드
- 필드명: `candidatePolicy`, 값: `"v1"`.
- **생략:** 서버는 `v1`로 취급한다(AI 문서에 명시).
- **미지원 값:** AI는 422로 거절한다. 백엔드는 v1 외 값을 만들지 않는다.
- **전송 게이트:** AI 수용 버전 배포 → contract smoke 통과 확인 → 백엔드가 `candidatePolicy`를 포함하는 설정을 켠다. 설정은 기본 OFF로 두고 운영 전환은 별도 PR로 한다.

### 1.3 지역 코드
- 카탈로그 canonical 형식만 허용한다(예: `kr-11`, `kr-11-jongno`). 현재 `catalog_regions.code` 형식과 대조한다.
- 존재하지 않는 코드는 `POST /api/v1/explorations`에서 **400 VALIDATION_ERROR**로 거절한다. 조용히 0건으로 끝내지 않는다.
- 지역 없는 요청은 정상이며 전국 후보로 처리한다.

### 1.4 후보 snapshot 식별자 (평가 재현성)
- 해시 입력: `candidatePolicy` + 순서 있는 후보 장소 ID 목록 + 지역 코드(없으면 빈 값) + 데이터셋 revision.
- 알고리즘: SHA-256, 결과 앞 16자 이상을 `candidateSnapshotId`로 쓴다.
- 기록 위치: 여정 run의 AI 요청 로그와 평가 실행 기록. 후보 순서가 바뀌면 값도 바뀌어야 한다.
- 질의 원문은 해시 입력에서 제외한다(개인정보·재현성 분리). 원문은 AI 골드셋 쪽에서 관리한다.

## 2. corpusRevisionId 및 useRag

### 2.1 현재 단계 (AI 변경 전)
- 백엔드 요청에 `useRag`와 `corpusRevisionId`를 **보내지 않는다**.
- `ONMARU_RAG_ENABLED`는 운영 compose에서 `false`로 유지한다. 현재 기본값과 일치하는지 PR 전에 확인한다.

### 2.2 AI 수용 후 단계
1. AI 수용 버전 배포 후 `useRag` 생략(또는 `false`)으로 **기존 비RAG 여정**을 먼저 검증한다.
2. AI가 RAG 준비 완료(activation log, retriever, hybrid 연결, retrieval smoke, 평가 승인)를 확인해 준 뒤에만 요청 단위 opt-in을 시작한다.
3. opt-in 시에도 `corpusRevisionId`는 백엔드가 보내지 않는다. AI 서버가 검증된 활성 revision을 선택한다.

### 2.3 기록 규칙
- 검색이 **실제로 시도된 경우**에만 AI 응답의 `corpusRevisionId`를 기록한다.
- 시도하지 않은 경우(`useRag` 생략, 서버 플래그 OFF)는 "미시도"로 기록하고 revision 값을 채우지 않는다.
- 기록 항목: `useRag` 요청값, 서버 플래그 상태, 응답 `corpusRevisionId`, `ragEvidenceRefs` 수, `ragDegradedReason`.

## 3. 빈 후보와 응답 호환성

### 3.1 빈 후보 계약
- 입력 `candidateRefs=[]`, `candidateCount=0`은 **유효한 빈 후보 요청**이다.
- 기대 응답: `outcome=NO_RESULTS`, `degradedReason=NO_CANDIDATES`.
- 백엔드 검증: 후보 0건을 만들었을 때 AI 호출 전에 빈 후보 흐름이 `NO_RESULTS`로 저장되는지 확인한다. 계약 오류(400)나 RAG 검색 실패와 섞이지 않게 별도 테스트를 둔다.
- AI 쪽 테스트 근거를 요청한다(실제 응답 본문).

### 3.2 추가 응답 필드 처리
- 선택 필드: `candidateCount`, `relaxedConstraints`, `degradedReason`(NO_CANDIDATES, NO_RELEVANT_CANDIDATES), `ragEvidenceRefs`, `ragDegradedReason`, `corpusRevisionId`.
- 알 수 없는 필드가 있어도 기존 역직렬화(`runId`, `outcome`, `orderedRefs`)는 실패하지 않아야 한다. `HttpAiProposalClientTests.toleratesOptionalRagAndExplanationFieldsFromNewerAiResponses`로 선택 필드 호환성을 검증한다. (이전 문서의 `ignoresUnknownResponseFields…` 테스트는 존재하지 않았음을 정정한다.)
- `orderedRefs ⊆ candidateRefs` 규칙은 그대로 유지한다. 임시 ID는 기존처럼 거절하고 baseline으로 떨어진다.

### 3.3 구분 규칙
| 상황 | 결과 | 처리 |
|---|---|---|
| 후보 0건 | NO_RESULTS / NO_CANDIDATES | 정상 종료, 정보 로그 |
| 후보는 있으나 질의와 무관 | NO_RESULTS / NO_RELEVANT_CANDIDATES | 정상 종료, 정보 로그 |
| 요청 계약 위반 | 400/422 | 실패로 기록, 알림 대상 |
| RAG 검색 실패 | 응답 정상 + ragDegradedReason | RAG 없이 진행, 사유 기록 |

## 4. 이미지와 검증 증거

### 4.1 digest 확인 결과
- 공식 v0.1.3: `sha256:93009d02…`. GHCR 태그 `fc9353694…`에 연결되어 있고, 소스 태그 `v0.1.3`은 commit `fc93536`이다.
- 운영 배포 digest: `sha256:93009d02…` (배포 run `42961188005` 로그).
- `sha256:772d41a8…`: GHCR 공식 이미지가 아니다. AI 개발 머신 로컬 tag `onmaru-ai-engine:0.1.3-fixed`에서 확인된 값이며, 빌드 원본 commit은 확인되지 않았다. **평가 기록과 운영 근거에서 사용하지 않는다.**

### 4.2 증거 구분
| 항목 | 상태 | 근거 |
|---|---|---|
| 이미지에 `retrieval/lexical.py`, `vector.py`, `hybrid.py`, `rerank.py` 포함 | **확인됨 (모듈 포함)** | `93009d02` 이미지를 pull해 파일 존재 확인 |
| 레거시 journey proposal 경로에서 검색 실행 | **미확인 (실행 증거 없음)** | 코드상 hybrid 조정자는 `OrchestrationEngine`(plan-execute 구성)에만 연결됨. 레거시 경로 호출 없음 |
| RAG 검색기·activation log 연결 | **미연결** | `create_app()` 기본 인자에서 `rag_retriever=None`, `rag_activation_log=None`. 플래그를 켜도 RAG 응답은 빈 값 |
| 빈 후보 → NO_RESULTS/NO_CANDIDATES | **코드상 확인, 테스트 근거 대기** | `internal_auth.py` 217~218행. 실제 응답 테스트 필요 |

### 4.3 공유할 검증 결과 (요청 목록)
1. AI 수용 버전 배포 digest, contract smoke 결과
2. 배포된 digest에서 `candidatePolicy` 생략 요청과 `v1` 요청 결과
3. 미지원 값 422 결과
4. `useRag` 미사용 상태의 기존 여정 smoke (백엔드 `journey-smoke.sh` 기준, 지역 있음·없음 각 1건)
5. 빈 후보 응답 본문(`outcome`, `degradedReason`)

## 5. 평가 기록 스키마 (백엔드 제공 항목)

| 필드 | 값 예시 | 출처 |
|---|---|---|
| `candidatePolicy` | `v1` / 생략 시 `v1` | 백엔드 요청 |
| `candidateSnapshotId` | 16자 이상 해시 | 백엔드 계산 |
| `candidateRefs` 순서 | 순서 있는 목록 | 백엔드 요청 |
| `candidateCount` | 0–12 | 백엔드 요청 |
| `regionCode` | `kr-11` 또는 빈 값 | 백엔드 요청 |
| `useRag` (요청값) | 생략/false/true | 백엔드 요청 |
| `serverRagFlag` | on/off | 배포 설정 |
| `corpusRevisionId` | AI 응답값 (검색 시도 시에만) | AI 응답 |
| `outcome`, `degradedReason`, `relaxedConstraints` | — | AI 응답 |
| `imageDigest` | `sha256:93009d02…` | 배포 기록 |
| `datasetRevision` | 카탈로그 활성 revision | 백엔드 |

AI 골드셋 ID와 기준선·RAG 실행 ID는 AI팀 기록과 연결해 사용한다.

## 6. 구현 순서

### 6.1 지금 가능 (전송 없음)
1. `candidateSnapshotId` 계산과 로그 기록 (요청 필드 변경 없음)
2. 지역 코드 canonical 검증과 400 거절 (`POST /api/v1/explorations`)
3. `candidatePolicy` 상수 `v1`과 규칙 문서화, 랭커와 문서 값 일치 테스트
4. 빈 후보 테스트: AI 호출 전 흐름과 `NO_RESULTS` 저장
5. 추가 응답 필드 호환성 테스트 확장
6. 실패 중인 웹 테스트 5개 정리 후 PR

### 6.2 AI 수용 배포 후
7. `candidatePolicy` 전송 설정 추가 (기본 OFF)
8. 운영 smoke: 생략 → `v1` → 422 확인 (다른 정책 값 테스트는 스테이징 또는 로컬 AI에서)

### 6.3 평가 통과 후
9. `useRag` opt-in 설정 추가 (기본 OFF, 요청 단위 시험)
10. `ONMARU_RAG_ENABLED` 변경 PR. 롤백 기준과 함께 `production-ai-deploy`로 반영
11. 배포 후 smoke: 빈 후보, 지역 있음, 지역 없음, RAG on/off 각 1건, `corpusRevisionId` 기록 확인

## 7. 롤백 기준

- 여정 요청 실패율 급증, 422 발생, `CORRELATION_INVALID` 발생 → 즉시 `useRag` 전송 중단과 `candidatePolicy` 전송 중단
- RAG 관련 응답 지연 또는 `ragDegradedReason` 비율이 AI 평가 기준을 넘음 → `ONMARU_RAG_ENABLED=false`
- AI 이미지 이상 → 이전 digest `sha256:df8229b3…`(v0.1.2)로 롤백. 단, v0.1.2는 span 0 거절 규칙에 영향을 받지 않도록 백엔드 span 수정이 운영에 있어야 한다

## 8. 완료 조건 매핑

| 완료 조건 | 이 문서의 위치 | 현재 상태 |
|---|---|---|
| 1) AI 수용 배포·smoke 전 candidatePolicy 전송 금지 | §0, §1.2, §6.2 | 전송 코드 없음, 설정 기본 OFF 예정 |
| 2) useRag 미전송, 서버 플래그 OFF 유지 | §0, §2.1 | 현재 미전송. compose 기본값 `false` |
| 3) 빈 후보 계약, 추가 응답 필드 호환 | §3 | 테스트 확장 예정 |
| 4) 정책 버전·snapshot·AI digest 보존 | §5 | 스키마 확정, 구현 예정 |
| 5) 실행 증거와 모듈 포함 증거 구분 | §4.2 | 구분 기록 완료 |

## 9. 진행 상태 (구현 기록)

| 항목 | 상태 | 근거 |
|---|---|---|
| 전국 후보 + 질의 관련성 랭킹, 지역 가산점 | 구현, 테스트 통과 | `JourneyRelevanceRankerTests`, `JdbcApprovedPlaceReadTests` |
| 질의 단어가 맞지 않을 때 전국 목록 대체 | 구현, 테스트 통과 | `ApprovedPlaceReader.readPool` 대체 경로 |
| 지역 코드 canonical 형식 + 카탈로그 존재 검증, 400 | 구현, 테스트 통과 | `ExplorationRegionValidationTests`, `CatalogRegionCodeCatalog` |
| 지역 없는 요청은 되묻지 않고 전국 검색 | 구현 | `ExplorationService.create` |
| 모든 생성 요청의 AI 쿼터 적용 | 구현 | `willCreateAiRun` 항상 true |
| 후보 snapshot 식별자 + 텔레메트리 (질의 원문 제외) | 구현, 테스트 통과 | `CandidateSnapshot`, `journey.worker.ai_request` |
| `candidatePolicy=v1` 상수 (요청 미전송) | 구현 | `CandidateSnapshot.POLICY_V1` |
| 빈 후보 → NO_RESULTS, 추가 응답 필드 호환 | 테스트 통과 | `HttpAiProposalClientTests` |
| 게스트는 여정 시작 불가 (401, 로그인 필요) | 현재 정책 | `ExplorationController.admitAiRun` |
| 실행 완료 시 활성 AI 슬롯 해제 | **미구현 (후속 이슈)** | 현재 cancel만 슬롯을 해제함 |
| `candidatePolicy` 요청 전송 | **보류** | AI 수용 버전 배포 + smoke 후 |
| `useRag` 전송 / `ONMARU_RAG_ENABLED` 변경 | **보류** | AI 평가 통과 후 |
