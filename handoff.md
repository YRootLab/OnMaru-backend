# handoff.md

## 2026-10-22 AI 엔진 저장소 이전 및 같은 Lightsail 배포 연동

- AI 설계 문서는 `YRootLab/onmaru-ai-engine` 저장소 `main`에 이전했고, backend의 `docs/ai-rag/README.md`는 포인터로 전환했다. AI 저장소 report builder도 AI 소유 문서만 묶도록 바꿨다. 과제 요구사항 PDF는 현재 backend 저장소에서 제거했다.
- 백엔드 후속 구현 범위는 `docs/ai-rag-spring/backend-integration-prd.md`에 통합했다. AI 저장소 문서는 목표 명세이며, 구현 완료를 뜻하지 않는다.
- 기존 FastAPI baseline 74개 파일, 테스트/평가 자료를 AI 저장소 루트로 옮겼고 PR #1을 `eaaeb1f`로 merge했다. main CI의 첫 Trivy scan은 `certifi 2026.7.22`의 GHSA-6q48-6c7w-9gw2 취약점을 찾아 실패했다. `certifi>=2026.10.19`로 잠금한 PR #2를 `0db5035`로 merge했고 후속 CI의 test, image build, Trivy scan, SARIF upload가 통과했다. image digest는 `sha256:5486ab8624c6b85b0b010068163f824c95c5082443d055f896d8f43220d7e889`이다. 운영 배포에는 사용하지 않았다. 현재 baseline은 결정적 16차원/in-memory이며 목표 384차원 pgvector + BM25 RAG 구현을 의미하지 않는다. 로컬 pytest 305 passed/1 skipped, Ruff, mypy, eval contract 통과. 로컬 Docker daemon이 꺼져 local image build는 미실행이지만 GitHub CI build는 통과했다.
- backend 인프라에 disabled-by-default `ai-engine` Compose profile을 추가했다. Spring의 내부 URL은 `http://ai-engine:8000`; FastAPI는 Docker 내부 `app` network 전용이고 host port를 publish하지 않는다. `.env.example` 기본 `ONMARU_AI_ENABLED=false`; 1.7GB 미만 host나 가용 RAM/secret 사전조건 미충족 시 deployer가 거부한다.
- `deploy-ai` 제한 SSH 명령과 별도 digest-only `workflow_dispatch`를 추가했다. AI image 교체 health 확인 실패 시 기존 container/image digest를 복구한다. 운영 activation은 하지 않았다. 2GB Lightsail 업그레이드, staging 혼합 부하 검증, image CI merge/publication, GitHub package access 설정, 운영 `.env` 설정, 새 deployer installer 설치가 선행되어야 한다.
- backend 검증: 배포 관련 Node test 20/20, `sh -n`, `docker compose ... config --quiet`, `python3 scripts/validate_contracts.py`, `git diff --check` 통과. 전체 Node test는 234개 중 232 pass/1 skip/1 fail이며 실패는 기존 `benchmark-compare.test.mjs`의 missing-result reason assertion이다. Spring Gradle test는 468개 중 44개가 Testcontainers의 Docker socket 미실행으로 초기화 실패하고 2개 skip했다. Docker daemon이 켜진 환경에서 `verify`와 host-level rollback rehearsal을 재실행해야 한다. Backend workspace의 넓은 기존 dirty changes는 보존하고 관련 파일만 수정했다.
- AI 설계 문서는 이전됐지만 BE `ai/` 소스 경로와 CI 참조는 AI PR merge 후 후속 정리가 필요하다. PR merge 전 source를 지우면 기존 CI가 깨지므로 유지 중이다.

## 2026-10-19 홈 여정 AI RAG 새 설계 정리 (#776)

- 2026-10-22 MCP tool 설계 확장: Spring AI `@McpTool` 기반 online `journey_execute_approved_lookup`과 quality harness 전용 read-only tools를 분리해 제안했다. FastAPI query router는 deterministic이고, approved opaque grant가 있을 때만 Python MCP client가 fixed lookup을 호출한다. SQL_ONLY는 LLM/embedding/retrieval을 건너뛰며 SQL+retrieval은 MCP lookup과 내부 검색을 결합한다. 계약은 `docs/ai-rag-spring/journey-query-mcp.md`, `docs/contracts/mcp/journey-query-tools.v1.json`, ADR-0019 및 관련 README/PRD/query-processing/request lifecycle/diagram에 반영했다. ADR와 전체 MCP 구현은 미승인·미구현 제안 상태다.

- 2026-10-22 명명 결정: 별도 Python 저장소는 `YRootLab/onmaru-ai-engine`, 그 안의 비동기 품질 개선 논리 모듈은 `onmaru_ai.quality_harness`로 표기한다. `docs/ai-rag/README.md`, 모듈 경계, 품질 감독 문서와 `docs/ai/README.md`에 반영했다. 현재 코드 `ai/`와 기존 `evals/`는 이동하지 않았고 GitHub 공개 빈 저장소는 2026-10-22에 생성했다. 공유 내부 계약·독립 CI·배포 경로 검증 뒤에 분리한다.
- 2026-10-21 PRD/README 링크 점검: `docs/ai-rag/README.md`와 `prd.md`가 각각 같은 폴더의 다른 Markdown 15개 전부를 링크함을 확인했다. PRD의 제품 성공·인수 기준에 claim의 의미상 지지·보류, 기존 Grafana/Prometheus/OTel에서 단계/비용/유실 확인을 추가했다. `git diff --check`와 ADR 16건 통과. 구현 완료를 뜻하지 않는다.
- 2026-10-21 `docs/ai/` 중복 문서 정리: `retrieval-and-rag.md`는 optional RAG와 reranker 후순위의 과거 초안이어서 제거했다. 참조하던 문서는 역사적 판단이면 ADR-0007, 새 구현 지침이면 `docs/ai-rag/`로 연결했다. `journey-guardrails.md`, `proposal-validation.md`, `evaluation-harness.md`는 기존 코드·공개 계약의 참조가 남아 유지한다. `application-architecture.md`, `corpus-sync-contract.md`, `gemini-provider-adapter.md`, `internal-service-authentication.md`는 공통 경계/기존 adapter 계약으로 유지한다. 변경된 Markdown 상대 링크 0건 누락, ADR 16건, `git diff --check` 통과. 전체 저장소 링크 검사에는 기존 입력 스냅샷의 깨진 링크가 남아 있으나 이번 변경 범위 밖이다.
- 현재 브랜치: `docs/776-ai-rag-prd`. 이번 작업은 문서만 변경하며 코드·migration·운영 DB·배포는 건드리지 않았다.
- **새 진입점:** `docs/ai-rag/README.md`는 심볼릭 링크가 아닌 일반 파일이며, 제품·오프라인/온라인 흐름·인프라·출시 순서를 종합한다. 상세 PRD, 아키텍처, API/저장 모델, 요청 생명주기, 보안·오남용, 운영·평가를 같은 폴더에 둔다. `docs/ai/`는 공통 AI 계약과 기존 구현의 역사적 설명으로 구분한다.
- 사용자 결정: 회원 대화형 홈 여정 검색, 대화 재개와 홈/결과 목록, `/c/{threadId}`, 찜한 대화의 마이페이지 노출, 장소 3~5곳(부족하면 적게), SSE 진행 표시와 확정 snapshot, LLM 전송 시도 주 5회·월 15회, 첫 출시부터 lexical+pgvector 하이브리드 RAG. 회원 대화는 마지막 사용자 질문 뒤 6개월 보관하며 찜한 대화에도 동일하게 적용한다.
- 2026-10-21 비용·역할 결정: 사용자 답변은 Google AI Studio의 Gemini API **무료 tier**를 사용한다. 월 7천 원 Antigravity CLI 구독은 운영자가 AWS에서 직접 수행하는 품질 분석·실험 검토용이다. 공식 FAQ상 개인 CLI를 타사 서비스 내부에 감싸는 방식은 지원되지 않으므로 회원 요청·상시 자동 supervisor에는 연결하지 않는다. 무료 tier의 Google 제품 개선용 데이터 처리 조건을 고지하고 전송 payload를 제한한다. 무료 quota 소진 시 유료 전환 없이 기본 탐색으로 종료한다.
- 2026-10-21 품질 운영 결정: 정기 작업은 trace·gold 평가·실패 신호 집계와 운영자 검토 큐 생성까지만 자동으로 수행한다. Antigravity CLI 분석과 개선 실험은 사용자가 필요할 때 범위·quota/추가 비용을 검토하고 직접 시작한다. 제안만으로 실험·승격하지 않는다. 안전 사고 차단·경보는 예외 없이 자동 유지한다.
- 2026-10-21 품질 알림 결정: 일반 품질 저하는 영속 검토 큐에 중복을 묶어 쌓고 매주 **Discord와 이메일 양쪽**의 요약 알림 및 Grafana 집계 대시보드에서 확인한다. 개별 항목은 기존 `/admin`의 `ADMIN` 전용 `/admin/ai-quality` 화면에서 열고 사용자가 선택해야 조사→실험→검토 파이프라인이 시작된다. 큐 항목은 최초 생성부터 최대 3개월 보관한다. 관리자 화면에서 Antigravity CLI를 서버가 실행하지 않고, 운영자가 비식별 패킷을 본인 세션에서 직접 분석한다.
- 2026-10-21 용량·일정 결정: 기존 1GB/$7 Lightsail을 **2026-10-23 금요일 오후(KST)**에 스냅샷 기반 **2GB/$12 새 인스턴스**로 마이그레이션할 예정이다. 정확한 시각은 미정이며 아직 실행하지 않았다. 같은 새 호스트에 Spring·PostgreSQL/PostGIS/pgvector/BM25·Nginx·FastAPI·질문 임베딩·reranker를 모두 둔다. 문서 임베딩·재색인은 온라인 피크와 직렬화하고 2GB 모델 포함 혼합 부하를 출시 전에 검증한다. [마이그레이션 runbook](docs/operations/runbooks/lightsail-2gb-migration.md)에 DB 논리 backup·스냅샷·새 인스턴스 방화벽·고정 IP 전환·롤백 경계를 작성했다.
- 운영 결정(2026-10-21 재수정): 별도 $5 벡터 DB·FastAPI 서버를 만들지 않는다. 동일 호스트 전용 Docker 네트워크·TLS·AI 전용 DB role과 공개 포트 미노출을 검증한다. PostgreSQL BM25 확장은 **첫 RAG 릴리스의 1차 후보**이고, Elasticsearch/OpenSearch는 별도 자원·비용이 필요하므로 현재 출시 구성에서 제외한다. 2GB 마이그레이션은 RAG 구현·출시와 별개로 검증한다.
- ADR-0007은 ADR-0017로 대체했으며 삭제하지 않았다. 기존 AI/RAG 초안 중 중복·충돌하는 미커밋 파일은 새 설계로 내용을 이전한 뒤 제거했다. 기존 코드가 사용하는 단발 proposal/최대 3개 계약은 실제 전환 전까지 문서에서 역사적 구현 계약으로 표시한다.
- 검증: 변경·신규 설계 문서의 상대 링크 0건 누락, `adr.py validate --dir docs/decisions --json` 16건 통과, `git diff --check` 통과. OpenAPI/SSE/schema와 실제 인프라 변경은 이번 문서 작업에 포함되지 않았다.
- 후속 대조: 지금까지의 대화 결정을 `docs/ai-rag/README.md`의 결정 추적표로 매핑하고, 홈/결과 사이드바, SQL/RAG 라우팅 표, 확정 턴 기반 메모리 갱신, 60문항 초기 평가셋과 자동 평가 job 조건을 추가했다. `request-lifecycle.md`에는 요청 불변식·라우팅 우선순위·내부 envelope·SSE 상태·실패별 과금과 fallback을 명시했다.
- AI application engineer 검토 반영: 질문 의미를 버전형 주제 code·canonical ID로 전달하고 표현 실패는 확인 질문으로 처리한다. 미검증 모델 토큰의 SSE 노출을 금지하고 확정 답변만 점진 표시한다. ledger 중단 복구, 긴급 철회 차단, 초기 품질·지연 게이트와 요청 예산, 개인정보 보존 정책의 운영 전 결정 게이트를 문서화했다.
- 추가 구체화: `docs/ai-rag/question-understanding.md`에 코드·동의어·부정·복합·다턴 규칙, `rag-storage-schema.md`와 `docs/database/modules/ai.dbml`에 revision·문서·청크·vector(384)·철회 스키마, `grounding-and-review.md`에 claim별 허용/보류와 이중 검수, `evaluation-and-environment-tests.md`에 120문항 평가 및 로컬/staging 환경 시험 계약을 추가했다. Azimutt SQL 산출물도 재생성했다.
- 이번 검증: DBML→PostgreSQL SQL 변환, Azimutt 산출물 생성, 로컬 Postgres Compose 설정, `bash scripts/verify-contracts`, ADR 16건 검사, 문서 상대 링크, `git diff --check` 통과. Docker daemon 미연결로 실제 pgvector 설치·SQL/부하 시험은 미실행이며 staging도 아직 측정하지 않았다.
- 다음 단계: OpenAPI/SSE/schema 및 FE 계약 구현 작업 분해, 로컬/격리 스테이징의 pgvector·BM25·비공개 DB 연결·1GB 혼합 부하 검증. 실서버 변경은 아직 없다.

> 아래 같은 날짜의 `홈 여정 탐색 AI·RAG 설계 정리`는 이번 개편 **이전의 세션 기록**이다. P0~P6, optional RAG, 위반 누적 쿨다운, $7 서버 유지 등의 제안을 현행 계획으로 사용하지 않는다.

## 2026-10-19 홈 여정 탐색 AI·RAG 설계 정리 (문서 전용, 코드 변경 없음)

- 상태: 설계 문서 5개 작성·수정 완료. 전부 **미커밋**이며 Issue도 아직 등록하지 않았다(AGENTS.md: 승인 후 Issue 생성 → 브랜치). 코드, migration, 운영·스테이징 DB는 건드리지 않았다.
- 작업 위치: worktree `AI-RAG-planning`. 세션 시작 시점부터 `docs/ai/home-natural-language-search-design.md`가 untracked였다(이 세션에서 만든 파일 아님).

### 변경 파일

| 파일 | 상태 | 내용 |
|---|---|---|
| `docs/ai/home-natural-language-search-design.md` | untracked (기존) | 홈 자연어 검색 RAG 초안. 이번 세션에서는 참조만 했다. |
| `docs/planning/journey-exploration/home-journey-flow-and-templates.md` | 신규 | 홈 구성 7블록, userflow 흐름도, 화면 상태 12종, 입력→템플릿 규칙, 템플릿 T1~T5, 슬롯 6종, 식사·숙소 처리, 소개글 스키마·검증, 열린 질문 6개, M1~M5 |
| `docs/ai/conversation-boundary-policy.md` | 신규 | 세션 상태 기계(NORMAL→WARNED→LIMITED→COOLDOWN), 토론 불참 원칙, 공개 응답 코드 제안, 관측·개인정보, 테스트 요구, 열린 질문 5개. §4는 우회 기법 문서 참조로 교체 |
| `docs/ai/prompt-attack-validation.md` | 신규 | 우회 기법 T-01~T-18 분류(구조 방어·탐지 신호·응답), 검증 파이프라인 8단계, 코퍼스 구성, 목표 수치, 회귀 게이트, 열린 질문 4개 |
| `docs/ai/rag-implementation-plan.md` | 신규 | 목표·비목표, 현재 상태 표, P0~P6 단계별 exit 기준, 교차 관심사, 리스크, 열린 질문 5개, Issue 분해 초안 12건 |
| `handoff.md` | 수정 | 이 항목 |

### 정한 것 (결정)

**1. 검색 방식**
- 자연어는 LLM이 장소를 고르지 않는다. 규칙 사전(지역·테마·제외)으로 `JourneyIntent`를 만들고 SQL 하드 필터 + lexical/trigram으로 후보를 찾는다.
- LLM은 P2 이후 후보의 **설명 문장**만 쓴다. 벡터(P3)는 P1 평가에서 nDCG@3이 +0.05 이상일 때만 켠다.

**2. 여정 결과물**
- 기본 결과는 **반나절 코스 보드**(슬롯 템플릿)다. N일 자동 일정은 후속(`journey-service-plan.md`, `product-prd.md` 근거).
- 후보는 MVP에서 카드 최대 3개. 가드레일상 LLM 입력은 후보 최대 12개, 응답은 최대 3개 `orderedRefs`.

**3. 템플릿 (T1~T5)**
- T1 고요한 한옥 반나절, T2 한옥 체험 하루, T3 시장 걷기, T4 오디 이야기 산책(데이터 확인 후 활성), T5 한옥과 시장을 함께(명시 요청에서만).
- **한옥 → 시장 고정 순서를 두지 않는다.** 시장은 자동으로 추가하지 않는다. 사용자가 "한옥만"이라고 하면 T5를 쓰지 않는다.
- 슬롯이 비면 빈 칸을 허용 범위 밖 카테고리로 채우지 않고 템플릿을 제외하거나 THIN_RESULTS로 응답한다.

**4. 음식·숙소**
- 맛집 카드는 만들지 않는다. 식사 슬롯은 "점심은 주변에서 자유롭게" 고정 문구다.
- 한옥 숙박은 허용 범위 안이고 일반 숙소는 밖이다. 숙박은 1일 코스 확장 때만 노출한다.
- "근처 맛집"을 범위에 넣을지는 **제품 결정 대기**다. 현재 가드레일 허용 목록에 없다.

**5. 소개글**
- LLM이 쓰되 후보와 근거 안에서만 쓴다. 출력은 구조화 스키마(`templateId`, `slotLines`, `placeRef`, `evidenceRefs`)이고, 고유명사·후보 ID·금지 사실을 검증한다. 실패하면 템플릿 기본 문장을 쓴다. P2 이후 작업.

**6. 대화 경계와 우회 방어**
- 1차 방어는 **구조**다. LLM은 사용자 원문이나 대화 이력을 받지 않고 enum 값만 받는다.
- 누수 경로 2개를 확인했다.
  - `JourneyIntent.explicitConstraints: string[]`는 자유 문자열이다. **enum으로 바꿔야 한다**(P0 항목).
  - RAG 원천 텍스트 속 지시문은 ingestion 단계에서 탐지하고, 데이터 블록에 표지를 붙인다.
- 우회 기법 18종을 분류했다(역할극, 허구 프레임, 우선순위 조작, 권위 주장, 프롬프트 추출, 인코딩, 언어 전환, 분할, 구분자 위장, 다중 예시, 감정 압박, 토론 유도, 간접 인젝션, 위기 신호 등).
- 위기 신호(T-12A)는 우회가 아니라 **안전 안내 경로**로 분리한다. 카운터를 올리지 않는다.
- 탐지 규칙은 로그와 카운터용 보조 신호다. 탐지 실패가 유출로 이어지지 않도록 설계했다.
- 공개 응답에는 어떤 기법에 걸렸는지 알려주지 않는다.
- 기본 탐색과 저장 보드는 모든 상태에서 허용한다. 정책 위반으로 서비스 전체를 막지 않는다.
- LLM 분류기는 intake에 쓰지 않는다. 오프라인 평가에만 쓸 수 있다.

**7. 벡터와 인프라**
- 임베딩은 별도 $5 서버를 만들지 않는다. 벡터 저장과 검색은 기존 PostgreSQL + pgvector를 쓴다. 임베딩 생성은 배치 작업 또는 외부 API로 한다.
- 피벗 조건: 하루 변경분 800건 정도면 임베딩 비용은 하루 몇 원 수준으로 추정했다. 실제 요금은 확인이 필요하다.
- **사실 교정 필요:** `handoff.md` 2026-10-01 항목에 따르면 운영 Lightsail(`onmaru-prod-seoul`)은 **$7 번들, 1 GB RAM**이며, 그 한 대에 Spring + PostGIS + Nginx가 함께 돈다. 이번 세션 답변에서 "$5 = 1GB"라고 말한 것은 확인되지 않은 기억이다. 현재 사양은 콘솔에서 확인해야 한다.
- 그래서 pgvector를 올리기 전에 **메모리 측정이 먼저**다. 앱과 DB가 같은 박스에 있으므로, $12 업그레이드가 필요하면 DB만이 아니라 박스 전체를 올려야 한다. 플랜 변경 시 고정 IP와 스냅샷을 확인한다.
- 스테이징은 AWS 온디맨드 staging을 유지한다. 릴리스 게이트(`deploy.yml` staging 배포와 smoke, Git Flow 검증)에 쓰이기 때문이다. 개발자의 일상 개발과 pgvector 실험은 **로컬 Docker**(Postgres+pgvector)로 한다. staging은 별도 database로 분리하고, 사용하지 않을 때는 재운다.

### 해야 할 것 (순서)

1. **사용자 승인 후 Issue 등록.** `rag-implementation-plan.md` §7의 12건을 P0부터 등록한다. 등록 전에는 브랜치를 따지 않는다.
2. **P0 결정 확정.** 대화 경계 정책 열린 질문 5개, userflow 열린 질문 6개를 정한다. 특히 카운터 기준(게스트 토큰 vs 회원), 쿨다운 시간, 맛집 범위.
3. **`explicitConstraints` enum 계약 전환.** 현행 가드레일 문서(`journey-guardrails.md`)와 `application-architecture.md`에 반영하고, provider 요청에 자유 문자열이 0건임을 테스트로 고정한다.
4. **평가셋 60문항 작성.** 개발 40, held-out 20. 템플릿 선택 정답과 우회 공격 세트(기법당 20개 이상), 정상 세트 300문장 이상을 포함한다. 공격 문장은 문서에 옮기지 않고 접근 제한 경로에 둔다.
5. **pgvector와 메모리 측정.** 로컬 Docker에서 pgvector 설치를 확인한다. 운영 `onmaru-prod-seoul`의 현재 메모리 여유와 OOM 기록을 확인한다. 결과로 $7 유지 또는 $12 전환을 정한다.
6. **로컬 Docker 개발 환경.** `infra/local`에 Postgres+pgvector compose가 있는지 확인한다. 없다면 만든다. staging 재가동은 릴리스 검증에만 쓴다.
7. **P1 규칙 검색 구현.** 지역 사전, SQL 필터, lexical/trigram, 템플릿 선택, 상태 기계 순으로 진행한다. 이 단계가 기본 경로이자 fallback이다.
8. **P2 동기화와 철회.** `tourapi_detail_snapshots` 기준 revision export, FastAPI 동기화, tombstone 테스트(철회 항목 검색 결과 0건)를 exit 조건으로 둔다.
9. **P3 벡터 실험.** 임베딩 정책(외부 API 허용 여부)을 확정한 뒤에만 시작한다. 판정 결과는 ADR 또는 평가 리포트에 남긴다.
10. **P4 소개글과 이유 문장.** Gemini 어댑터를 opt-in으로 붙인다. claim support 사람 검토 0.90 이상이 조건이다.
11. **P5 FE 연결.** 홈 API, 화면 상태 6종 구현, feature flag, staging fallback 검증.

### 열린 위험

- **단일 박스 메모리:** 운영 1 GB에 Spring, PostGIS, Nginx가 함께 있다(2026-10-05 기록: Spring 448 MiB, PostgreSQL 320 MiB, Nginx 64 MiB 제한). pgvector 추가 시 OOM 위험이 크다. 측정 전에는 벡터를 올리지 않는다.
- **임베딩 데이터 처리 조건:** TourAPI 개요 본문을 외부 임베딩 API로 보내는 것이 약관상 가능한지 미확인이다. 확인 전에는 원문을 외부로 보내지 않는다.
- **정책 오탐:** 정상 한국어가 차단될 수 있다. 구문 패턴 중심으로 설계했지만 실제 오탐률은 정상 세트로만 측정할 수 있다.
- **다국어 탐지 한계:** 영어 중심 사전이므로 다른 언어는 구조와 출력 검증에 의존한다.
- **홈 자연어 초안의 예시 질의:** `home-natural-language-search-design.md`의 예시 "비 오는 날 조용한 한옥 카페"는 P0 규칙으로 해석할 수 없는 주관 조건을 포함한다. 수정이 필요하다.
- **원천 이용 조건:** TourAPI 개요 재노출과 K-Contents 발췌의 이용 조건은 미확인이다. 확인 전에는 출처 표기 중심으로 노출한다.

### 검증 상태

- 문서 작성 후 링크·표 형식을 눈으로 확인했다. 자동 검증(markdown lint, mermaid 렌더링, `verify-contracts`)은 돌리지 않았다.
- 코드, 테스트, DB 변경이 없으므로 CI 영향은 없다. 문서 파일이 `docs/` 아래에 있으므로 PR에서 hygiene 검사가 돌 수 있다.
- 다음 세션 시작 시 확인할 것: `git status`로 위 5개 파일과 기존 untracked 파일이 그대로 있는지, Issue 등록 여부.

## 2026-10-12 Issue #718 운영 INSERT 전 작품 조사

- 사용자 지시: 11,083곳의 작품명을 먼저 찾아 검수하고 그 후 운영 INSERT를 진행한다. 운영 DB는 아직 변경하지 않았다.
- 브랜치 `feature/718-official-work-labels`. KCISA CSV 15,034행을 11,083곳과 다시 연결했다. 사용자 지시에 따라 아티스트 방문·촬영 콘텐츠(`ARTIST`)를 작품/콘텐츠 후보로 폭넓게 수용했다. 이에 따라 콘텐츠 후보가 있는 장소는 기존 319곳에서 378곳으로 확대되었고, 원천 장소명/검수 별칭·도로명 건물번호·250m 이내 좌표·장면 설명이 맞는 관계는 610건, 추가 검토 247건(총 857건)으로 증가했다.
- 로컬 산출물 `tmp/kcontents-718/official-work-labels.jsonl`, `labels-with-works.jsonl`, `manifest-with-works.json`, `import-with-works-preview.sql`, `fe-place-labels.jsonl`. 이전 `import-reviewed-apply.sql`은 작품 조사 전 파일이므로 사용 금지. 새 작품 조사 해시를 V050/V051와 importer에 묶었다. 다음은 수동 작품/장면 검수 확대, 테스트·PR, 운영 마이그레이션 후 적재와 공개 관계 승격이다.

## 2026-10-12 Issue #718 전수 장소 요약·FE 내부 전달

- 브랜치 `feature/718-bulk-labeling`, PR #720. TourAPI 선별 후보 11,083곳 모두에 로컬 `placeSummaryPoints` 3개를 생성하고 `manifest-reviewed.json`에 내용 SHA를 기록했다. 기존 공식 촬영지 대조는 17곳 검증, 신규 1,752곳 검토 후보, 그 외는 공식 자료 한 종류에서 근접 근거가 없는 상태다. 이는 작품 관계 전수 검증 완료가 아니다.
- `tmp/kcontents-718/fe-place-labels.jsonl`/manifest는 FE #366 대조용 내부 산출물이다. `placeId`는 기존 공개 ID 8,678개와 read store 동일 fallback 2,405개이며 새 후보의 작품 관계·이미지는 공개 미승인이다. KCISA CSV에 이미지 URL 없음.
- V050에 요약 SHA와 3개 항목 CHECK를 추가했고 로컬 import SQL은 기본 ROLLBACK이다. 운영 DB는 아직 미적재, 공개 FE API도 새 요약을 읽지 않는다. 다음은 CI `verify`, PR 병합, release/migration, 운영 STAGED import와 검증, 승인 게시 게이트·FE API 연결이다.

## 2026-10-12 Issue #716 운영 카탈로그 선별 전환

- 사용자 지시: 기존 49,663건 전체 호출→23,598 활성 장소 경로를 신분류 34코드와 구분류 한옥·고택·궁궐·전통마을·한옥카페·한옥숙소·전통체험·전통시장 8필터, 관련 키워드 8개로 좁혀 교체한다. 이전 revision/identity/관계는 물리 삭제하지 않는다.
- 기존 운영 원천 읽기 전용 기준선: 신분류 9,801행, 구분류 4,685행, 중복 3,461행, 합집합 11,025행. 키워드 고유 추가분과 재호출 결과는 미측정이다.
- 브랜치 `feature/716-selected-catalog-cutover`. `SelectedSourceFetch`와 TourAPI 어댑터에 구분류 호출·키워드 확장, 기존 카탈로그 sync의 선택 모드 플래그를 추가했다. 집중 테스트와 Spring 컴파일 통과. 운영 전환은 아직 수행하지 않았다.
- 운영 절차: `docs/operations/runbooks/selected-catalog-cutover.md`. 다음은 PR·CI, 운영 후보 실행/분포 검증, 배포와 포인터 전환, FE 회귀 확인이다.


## 2026-10-12 Issue #691 공공 촬영지 기반 운영 라벨링

- 운영 활성 TourAPI 23,598곳 중 전통·역사 우선 분류 7,282곳을 대상으로 한국문화정보원 촬영지 CSV 15,034행을 대조했다. 넓은 매칭 후보 577관계는 미검증이다.
- 정확한 장소명·주소·30m 이내 좌표와 장면 원문을 검토한 23관계/14장소를 운영 DB에 추가하고, 기존 3장소와 함께 선별 공개 revision을 17장소로 전환했다. 작품/관계 각 26건이며 공개 API에서 17장소·26작품·장면 요약을 확인했다.
- Codex 지원 요약은 원천 행별로 검토해 `VERIFIED`로 저장했다. 태그 23개는 `REVIEW_REQUIRED` 후보로 저장되어 FE에 아직 보이지 않는다. 이미지·작품 줄거리·연도·장르는 채우지 않았다.
- 실행 기록과 재현 SQL: `testing/kcontents-pilot/build_official_filming_seed.py`, `production-official-filming-reviewed.sql`, `production-official-filming-selected.sql`. 정책과 다음 범위: `docs/operations/runbooks/official-filming-enrichment.md`.
- 현재 로컬 n8n health 200. n8n의 14일 자동 실행·100→1000 파일럿·고유 50장소 목표는 미완료다. #691은 계속 열어 둔다.


## 2026-10-11 Issue #691 운영 실데이터 시연 준비

- 사용자 요청: 내일 FE 시연을 위해 운영 DB의 실제 장소 데이터를 읽고 촬영 관계를 근거로 라벨링한다. staging은 합성 데이터로 검증할 수 있으나, 운영 라벨을 합성 사실로 채우지 않는다. 1만 후보 전체에 LLM을 호출하지 않고 검색·규칙 선별 후 근거가 있는 후보만 추출한다.
- 현재 브랜치: `feature/691-production-labeling-demo`. 운영 `master`에는 K-Contents V046~V049와 신규 API가 아직 없고, `develop`에만 있다. 운영 공개 `screen-hanok`은 2026-10-11 조회에서 `total=0`; FE 7개는 fallback이다.
- 준비: 관리 SSH를 통해 운영 PostgreSQL의 전용 읽기 전용 계정으로 `testing/kcontents-pilot/read-only-demo-candidates.sql`을 실행했다. 기존 FE 7개 대상은 모두 활성 TourAPI revision에서 확인됐고, `양진당`은 상주와 안동 두 장소가 검색되므로 주소 대조가 필수다. 관광공사 공식 근거 후보 3건의 장소 contentId·주소·공개 ID를 `production-demo-evidence.md`에 기록했다. 운영 `k_contents`와 관계 테이블은 아직 없다. 운영 입력은 수행하지 않았다.
- 릴리스 준비: `develop` → `release/v0.3.44` 초안 동기화 PR #708 생성. 검증과 운영 배포는 미완료다.
- 다음: 원문·권리 및 관계를 검수하고 승인된 selected discovery 게시 절차를 준비한다. PR #708 검증 후 `release/*` → `master`, 운영 migration·feature flag·실제 관계 입력·FE API 응답 확인이 필요하다.

## 2026-10-08 Issue #685 주간 선별 탐색 revision

- 브랜치: `feature/685-selected-discovery-revision`, Issue #685. `develop`의 병합된 #682 W1·#683 W2와 별도 V047 스키마를 연결했다. #686 V048은 뒤따르는 migration이다.
- 구현: 같은 공식 TourAPI taxonomy Set을 수집 격리와 의미 판정에 주입한다. 목록·상세 SHA-256/버전, ADDED·CHANGED·UNCHANGED·MISSING, 페이지 checkpoint와 상세 호출 상한, 후보/격리 dry-run, 사람 승인에 따른 별도 신규 active revision을 둔다. 기존 일일 cron·`kto-korean-tour` active pointer·공개 API는 수정하지 않는다.
- 안전: 승인 전 자동 공개 없음. 상세 merge 후 hash/fingerprint를 preview하고 one-shot 명령으로 근거·역할·권리를 확인해 승인한다. 승인 철회는 `selected_discovery_public_visible` view에서 즉시 숨긴다. 실패·빈 snapshot·상세 예산 초과·누락·기존 공개 행 변경은 신규 LKG pointer를 보존한다. 첫 게시 CAS와 source identity 충돌 처리를 추가했다.
- 검증: 선정 diff/승인/scheduler 집중 Gradle 테스트, PostGIS V047 migration·legacy pointer/공개 ID 보존·identity 공유·첫 게시 CAS·철회 view·rollback 통합 테스트, migration registry/DBML/Azimutt 계약, planning inputs, branch parser가 통과했다. PR의 `verify`가 최종 게이트다.
- 다음: PR을 `develop` 대상으로 열고 `verify` 확인. 부모가 Wave 통합에서 #685(V047)→#686(V048) 순서로 병합한다. 운영 자동 수집은 기본 비활성이고 #691 파일럿에서만 별도 승인 레코드로 단계적 공개한다.

## 2026-10-08 Issue #603 실행: 완료된 #681 Wave 0 기록

- 사용자 요청: #681의 실데이터 정책 검증을 먼저 완료하고, `blocked-by`가 해제되는 #682~#691을 각 독립 worktree·브랜치·`develop` 대상 PR로 병렬 구현한다. 각 PR은 `verify`와 Wave 통합 gate 뒤 병합한다. #691에서 100→1,000건 pilot·스테이징·롤백까지 수행하고 운영 배포 직전 결과/위험을 보고해 승인을 기다린다.
- 현재 브랜치/Issue: `docs/681-catalog-policy-validation`, #681. TourAPI 34개 코드 9,801건/41페이지, 4개 구제 검색의 신규 ID 57건, 운영 readonly 원천 49,613·공개 23,637건을 2026-10-08 실측했다. 원본 capture는 Git 밖의 `/tmp/onmaru-681-tourapi.json`과 `/tmp/onmaru-681-db.jsonl`에만 있으며 SHA는 `policy-validation.md`에 기록했다.
- 수정: `docs/planning/place-kcontents/policy-validation.md`, ADR-0016 초안, 공개 API 기준선 fixture, 재현 스크립트. 신규 정책 `discovery-candidate-v1.0.0`의 후보 dry-run은 INCLUDE 1,983/REVIEW 4,463/EXCLUDE 3,412다. INCLUDE는 게시 허가가 아니며 상세 quality gate가 남는다.
- 검증: 정책 4개 테스트, `verify-contracts --contracts-only`, planning inputs, branch parser, 문서 집계 일관성은 통과. 로컬 전체 Node 테스트는 `/tmp/onmaru-ci-toolkit-9c6f003/src`의 불완전한 별도 Toolkit 설치를 집어 24개가 실패했고, 동일 테스트의 고정 fixture 경로를 지정한 release 비교 24개는 통과했다. GitHub `verify`가 최종 gate다.
- 완료: #681 PR #692가 `develop`에 병합됐고 Issue가 닫혔다. #682/#683/#684도 병합·종료되어 #685의 선행 조건이 해제됐다. #569의 FE 실제 경로는 미검증이므로 열어 둔다.

## 2026-10-03 Issue #552 회원 익명 프로필·온기 후기 작성자
## 다음 작업: Issue #675 스테이징 pagination 실검증

- 브랜치: `feature/675-staging-pagination-fixtures`, Issue: [#675](https://github.com/YRootLab/OnMaru-backend/issues/675) OPEN. controller 리뷰 후 `develop` 대상 PR을 생성하며 본문은 `Refs #675`를 사용한다. 실제 Lightsail 배포·공개 API 3페이지 검증까지 Issue를 닫지 않는다.
- 변경 범위: `infra/lightsail/staging/seed.sql`, `StagingFixtureTests.java`, staging README·FE 안내, deploy pipeline 문서 계약 테스트와 `docs/superpowers/{specs,plans}/2026-10-07-staging-pagination-fixtures*.md`. 아래 로컬 검증 결과를 기준으로 PR CI `verify`에서 전체 hygiene 결과를 재확인한다.
- 병합 후: `CI / verify`가 통과한 병합된 `develop` SHA를 확인하고 `gh workflow run deploy.yml --ref develop -f deploy_staging=true`를 실행한다. workflow URL·SHA·이미지 digest가 일치하고 staging deploy가 성공한 뒤 제한 SSH `start`로 기동한다.
- 실검증: 운영 `/api/v1/health` 전후 확인 → staging `/api/v1/visit-reviews?scope=ALL&limit=30`, `/api/v1/odii/stories?language=ko-KR&limit=30`의 응답 cursor를 다음 요청에 그대로 전달해 기준 상태의 30/30/5, fixture 65개 unique ID, 마지막 `hasMore=false`·`nextCursor=null` 확인(공개 사용자 후기가 있으면 실제 끝까지 순회하고 늘어난 합계·페이지 수와 추가 ID를 별도로 기록) → `/api/v1/map/info/places`의 SPOT/CAFE/MARKET별 전체 cursor 순회 34/33/33 및 합계 100개 unique public ID, viewport 줌 1/6/9/11의 PLACE/CLUSTER/DISTRICT/REGION 필터 확인 → 대표 장소 상세·후기·오디오 연결 확인 → SSH `stop`·`status` 및 운영 health 확인. workflow URL·병합 SHA·digest·각 응답 결과를 #675에 기록한 뒤 완료 조건을 대조해 종료한다. SSH·배포 절차는 [스테이징 README](infra/lightsail/staging/README.md)·[FE 안내](docs/operations/staging-fe-guide.md)를 따른다.
- 최종 리뷰 수정: MockMvc 공개 controller·실제 JDBC 조회로 후기/Odii 30/30/5, 장소 후기 30/1·숨김 제외, Odii signed cursor와 동률 public ID ASC, 지도 4모드·카테고리별 ID를 자동 검증한다. 생성 집합 밖의 공개 #675 후기는 HIDDEN 처리하고 사용자 좋아요·신고·audit는 유지한다. 사용자 공개 후기 26개가 있는 DB는 fixture 65개를 보존하며 totalCount 91·30/30/30/1을 반환한다.
- 열린 위험: 병합 후 실제 Lightsail의 배포 SHA/digest·공개 API·운영 health 전후·staging stop 증적 확인이 남았다. 로컬 공개 JDBC 통합 검증과 별도로 실서버 완료 기준을 충족하기 전에는 #675를 OPEN으로 유지한다.

로컬 검증 재현 명령(저장소 루트에서 실행, 2026-10-07 결과):

```bash
# Task 4 수정 전 전체 Java CI baseline·bootJar 성공: 946 tests, 실패 0·skip 2; 당시 StagingFixtureTests 4/4
./gradlew --init-script build-logic/ci-performance.gradle.kts :adapters:tourism-api:test :modules:insights:test :modules:catalog:test :modules:audio:test :modules:community:test :modules:identity:test :modules:journey:test :modules:operations:test :apps:spring-api:test :apps:spring-api:bootJar --no-daemon -Ponmaru.ci.performance.enabled=true
# 최종 수정 관련 Java suite fresh 실행·bootJar 성공: 240 tests, 실패/오류/skip 0; StagingFixtureTests 8/8
./gradlew --init-script build-logic/ci-performance.gradle.kts :modules:catalog:test :modules:audio:test :modules:community:test :modules:shared-web:test :apps:spring-api:test --tests '*StagingFixtureTests' --tests '*VisitReviewQueryWebBoundaryTests' --tests '*JdbcVisitReviewStoreTests' --tests '*JdbcOdiiStoryReadStoreIntegrationTests' --tests '*OdiiStory*WebBoundaryTests' --tests '*MapInfo*Tests' --tests '*MapViewport*Tests' :apps:spring-api:bootJar --no-daemon --rerun-tasks -Ponmaru.ci.performance.enabled=true
# 최종 수정 전체 Node 229/229·skip 0: 불완전한 기존 로컬 Toolkit 대신 workflow 고정 SHA의 별도 checkout 사용
taskToolkitDirectory="$(mktemp -d /tmp/onmaru-675-toolkit-XXXXXX)"
git clone --quiet --no-checkout https://github.com/YRootLab/OnMaru-backend-ci-toolkit.git "$taskToolkitDirectory/repo"
git -C "$taskToolkitDirectory/repo" checkout --quiet --detach 7ecbb89aae771604d9c1c532cf123f239e279110
ONMARU_TOOLKIT_SRC="$taskToolkitDirectory/repo/src" node --test --test-concurrency=1 scripts/test/*.test.mjs
node --test scripts/test/deploy-pipeline.test.mjs # 7/7 통과
python3 -m pytest scripts/test/test_contract_validation.py # 11/11 통과
bash scripts/verify-contracts # 전체 계약·generated artifact 통과
node scripts/verify-planning-inputs.mjs # manifest hash·secret scan 통과
node scripts/validate-odii-fixtures.mjs # 8 fixture 통과
docker compose --env-file infra/lightsail/staging/.env.example -f infra/lightsail/staging/compose.yaml config --quiet # 통과
uv run --project ai pytest ai/tests # 304 통과·live smoke 1 skip
bash scripts/test/run-ai-evals.test.sh # re-exec version 테스트 통과
uv run --project ai python scripts/run-ai-evals --input ai/evals/journey-held-out-v1.json --output /tmp/onmaru-675-ai-eval-report.json --schema-output /tmp/onmaru-675-ai-eval-report.schema.json # quality/safety/latency/cost/determinism 통과
cmp ai/evals/report.schema.json /tmp/onmaru-675-ai-eval-report.schema.json # 일치
uv run --project ai ruff check ai # 통과
uv run --project ai mypy ai # 64 source files 통과
node scripts/print-branch-issue.mjs # 675
git diff --check origin/develop...HEAD # 통과
git diff --check # 통과
```
## 2026-10-08 Issue #677 DBML 계약 검증 도구 고정

- 브랜치: `fix/677-dbml-toolchain`, 관련 Issue: #677, 선행 PR: #676.
- 요청: CI contract job의 DBML 전이 의존성 drift를 재현 가능한 toolchain 설치로 복구한다.
- 구현: `scripts/dbml-toolchain`의 manifest·lock과 DBML 내부 패키지 override를 추가하고, wrapper가 매번 `npm ci` 이후 해당 로컬 CLI만 실행하도록 고정했다. 계약 테스트는 전체 lock, 경로·인수 전달, 설치 실패 시 stale CLI 실행 차단을 검증한다.
- 검증: wrapper 계약 2개 red→green, parse lock 버전 drift mutation 실패→복구 green, Node v22.20.0 실제 변환·전체 `bash scripts/verify-contracts`·생성 SQL diff 일치, Python 3.12 contract 12개, Node 228개 및 hygiene 통과. 전체 Node 검증은 비어 있는 공유 `/tmp/onmaru-ci-toolkit-9c6f003/src`를 우회해 별도 임시 checkout의 고정 Toolkit SHA `7ecbb89aae771604d9c1c532cf123f239e279110`를 `ONMARU_TOOLKIT_SRC`로 지정했다.
- 다음 단계: #677을 참조하는 develop 대상 PR 생성·리뷰, 필수 CI `verify` 확인 후 병합한다. PR #676의 실패한 contract 경로도 후속 CI에서 확인한다. npm 설치 단계는 CLI의 기존 전이 패키지 deprecation 경고를 출력하지만 검증은 성공한다.

## 2026-10-07 Issue #671 실시간 온기 CSRF 예외 및 MockMvc 호환성 수정

- 브랜치: `fix/671-warmth-csrf-bypass`, 관련 Issue: #671, PR: #672.
- 문제 원인: `CsrfProtectionFilter`에서 `request.getServletPath()`를 직접 사용하여 MockMvc 테스트 환경(기본 `servletPath=""`)에서 모든 요청의 CSRF 검증이 누락되어 11개의 WebBoundary 테스트가 실패함.
- 해결 내용:
  - `resolvePath(request)` 헬퍼를 추가하여 `request.getServletPath()`가 비어있는 경우 `request.getRequestURI()`로 fallback 하도록 처리하여 MockMvc 및 서블릿 컨테이너(Tomcat) 환경 모두에서 정상 동작 보장.
  - `schemaVersion(request)`도 `resolvePath(request)`를 사용하도록 일관성 개선.
  - `SecurityWebBoundaryTests`에 `POST /api/v1/realtime/warmth` 요청이 CSRF 토큰 없이도 204 No Content로 허용되는지 검증하는 `warmthBroadcastEndpointBypassesCsrf()` 테스트 케이스 추가.
- 다음 단계: PR #672 CI `verify` 통과 확인 후 PR 리뷰 및 merge.

## 2026-10-06 Issue #492 관리자 JWT JTI PostgreSQL 폐기

- 브랜치: `SHcommit/feat-security-jwt-jti-postgresql`, 관련 Issue: #492.
- 설계: 개별 logout은 SHA-256 JTI hash row, 계정 비활성화는 `tokens_valid_after` 경계로 분리했다.
- 구현: V044 migration, JDBC JTI/계정 경계 저장소, logout 즉시 access token 폐기, DB 장애 `503 AUTH_UNAVAILABLE`, production JDBC startup 검증, bounded cleanup, Micrometer 관측을 추가했다.
- 보안: token/JTI 원문과 signing secret은 저장·로그·metric tag에 포함하지 않는다. production에는 메모리 fallback이 없다.
- 검증: 관리자·migration 집중 Gradle suite 통과, Node/hygiene 228개 통과, 계약·fixture 검증 통과, AI 304개 통과·live smoke 1개 skip, `git diff --check` 통과. 전체 `./gradlew test`는 변경 범위와 무관한 `JdbcIdentityStampFlowTests.concurrentPartialProfileUpdatesPreserveBothFields`에서 로컬 PostgreSQL 연결을 6분 이상 대기해 thread dump 확인 후 중단했다. JTI hash, 별도 store 조회, 동시 upsert, expiry/cleanup, 계정 상태·iat 경계, logout 직후 401, 폐기 기록 장애 503은 집중 suite에서 모두 검증됐다.
- 다음 단계: 전체 검증, PR diff cleanup, `Closes #492`로 develop 대상 PR 생성. develop 병합 후 Issue reconcile 전에는 #492를 닫지 않는다.

## 2026-10-06 Issue #647 스테이징 공개 API fixture

- 브랜치: `feature/647-staging-api-fixtures`, 관련 Issue: #647.
- 요청: 소리마루 한 기능이 아니라 로컬/스테이징 FE가 호출하는 공개 API 흐름에 응답할 합성 fixture를 스테이징 DB에 제공한다.
- 구현: 기존 장소 2건 seed를 장소 4건, 후기 3건(공개 2·숨김 1), Odii story 3건으로 확장하고 지도 projection·장소 상세 이미지/태그·후기·장소↔오디오 연결을 고정 ID로 구성했다. `p-staging-hanok-a`가 대표 cross-surface fixture다.
- 안전: `onmaru_staging` DB 이름 guard와 운영 데이터 미사용 원칙을 유지하고, 모든 insert는 재실행 가능하도록 conflict 처리를 둔다. 스테이징 Odii 공개 host는 fixture 음원용 `samplelib.com`만 추가했다.
- 검증: `:apps:spring-api:compileTestJava`와 `git diff --check` 통과. PostGIS에서 seed 두 번 실행·연결 row·비-staging 거부를 검증하는 `StagingFixtureTests`를 추가했다. 로컬 디스크 full 당시 Docker VM 로그 쓰기가 실패한 뒤 engine socket이 timeout 상태여서 Testcontainers 실행은 GitHub CI 게이트에서 확인한다.
- 다음 단계: 코드 리뷰 반영, PR CI `verify` 통과 후 develop 병합, Issue reconcile, 검증된 develop image 스테이징 반영과 실제 공개 API smoke를 수행한다.

## 2026-10-05 Release v0.3.41 (#641)
## 2026-10-05 FE 실시간 온기 요청 사전 검토

- 현재 워크스페이스 브랜치: `feature/realtime-related-fe-requests`. 다른 개발자가 이 브랜치에서 후속 구현을 이어갈 예정이므로 현재 이름을 유지한다. 다만 저장소의 branch parser 규칙상 Issue 번호가 없는 브랜치이므로 PR 생성 전에는 적절한 GitHub Issue를 연결하고 브랜치 정책 충족 방법을 정리해야 한다.
- 요청 원문: `/Users/yangseunghyeon/Downloads/onmaru-backend-request.md`. 이번 세션에서는 요청서를 읽고 현재 Spring·Lightsail·Nginx·CSRF·CORS 구성과 대조했으며, 애플리케이션 코드·인프라 설정·요청 원문은 변경하지 않았다.
- 검토 결론: Live Presence는 AI 기능이 아니고 기존 비즈니스 API 및 SSE 경계와 맞으므로 FastAPI가 아니라 Spring MVC `SseEmitter`로 구현하는 안을 권장한다. 저장소에도 Journey SSE의 `SseEmitter`, heartbeat, 종료 콜백 구현이 이미 있다.
- 문서 정정 필요: `SseEmitter`가 열린 연결마다 요청 스레드를 계속 점유한다는 설명은 부정확하다. Servlet async가 요청 스레드를 반환하지만 `send()`는 블로킹될 수 있으므로 작은 bounded 전송 executor, 유한 큐, 전송 timeout 및 느린 연결 제거가 필요하다.
- 인스턴스 제약: 운영 Lightsail은 약 909 MiB RAM이고 Compose 제한은 Spring 448 MiB, PostgreSQL 320 MiB, Nginx 64 MiB다. JVM은 Spring 컨테이너 RAM의 60%를 최대 heap 기준으로 사용한다. 전체 SSE 상한 200개는 확정 용량이 아니라 검증 목표로 취급하고, 초기에는 50~100개 상한으로 시작해 heap·CPU·executor queue·기존 API latency를 부하 테스트한 뒤 조정한다.
- Nginx 현황: 운영과 스테이징의 일반 `location /`에 이미 HTTP/1.1, 빈 `Connection`, `proxy_buffering off`, `proxy_request_buffering off`, `proxy_read_timeout 130s`가 적용돼 있다. 구현 시 `/api/v1/realtime/` 전용 `location`을 추가해 `proxy_cache off`, `gzip off`, 긴 read timeout과 동일한 신뢰 프록시 헤더 정책을 명시적으로 적용하는 안을 권장한다.
- CSRF 현황: 이 저장소는 Spring Security의 `SecurityFilterChain`이 아니라 자체 `CsrfProtectionFilter`로 `/api/**`의 unsafe method를 검사한다. 익명이고 쿠키 인증이나 사용자 상태 변경이 없는 `POST /api/v1/realtime/warmth`만 정확히 예외 처리할 수 있으며, `permitAll` 표현 대신 자체 필터 예외라고 문서화해야 한다. CORS는 abuse 방어가 아니므로 clientId/IP/room/전체 rate limit은 별도로 필요하다.
- CORS 현황: `/api/**`는 환경변수 기반 exact-origin allowlist와 `GET`, `POST`, `OPTIONS`를 이미 지원하지만 전역 `allowCredentials(true)`를 사용한다. 실시간 경로는 credentials 없이 운영 FE origin을 허용하고, `localhost:3000`은 운영 allowlist가 아니라 local 또는 staging 환경에서만 허용하는 방안을 권장한다.
- 관련 Issue 조회: #519는 Lightsail 배포·운영 검증, #520은 기존 FE→BE 전달사항이며 이번 Live Presence 구현 전체를 직접 추적하지 않는다. 실제 구현 전에 중복 Issue를 다시 확인하고, 없으면 API 계약·Spring 구현·Nginx·보안 경계·부하 검증 acceptance criteria를 포함한 Issue를 생성한다.
- 다음 단계: Issue/브랜치 정리 → 요청 계약 확정(`roomId` allowlist, 오늘 방문자 정의 포함) → Spring SSE/POST 및 bounded resource 구현 → CSRF/CORS 경계 테스트 → 운영·스테이징 Nginx 전용 경로 추가 → 로컬/스테이징 부하 및 curl 인수 테스트 순서로 진행한다.

## 2026-10-01 Issue #561 운영 온기 히트맵

- 브랜치: `fix/561-warmth-heatmap`.
- 현상: 운영 온기 조회가 오래 걸리고, 서울·부산 `강서구`가 같은 잘못된 위치에 표시된다. 기본 날짜를 오늘로 보내는 FE 문제는 OnMaru-Frontend #267에서 별도 수정 중이다.
- 변경: 지역마다 활성 장소를 반복 조회하던 SQL을 활성 장소 중심의 집계로 바꾸고, 지역 ID 또는 부모 시도·시군구 주소가 정확히 맞는 좌표만 사용한다. PostgreSQL 회귀 테스트에서 동명 시군구의 좌표 분리를 검증한다.
- 검증: `./gradlew :apps:spring-api:test --tests '*JdbcVisitorObservationStoreTests' --no-daemon` 통과.
- 다음 단계: PR `verify`, 운영 규모 성능 확인, release/master 배포 후 실제 온기 응답 검증.

## 2026-10-01 Issue #553 운영 지도 장소 목록 500 복구

- 브랜치: `fix/553-map-projection-backfill`. 운영 `map/info` 화면이 500이었고, V036 이전에 게시된 활성 TourAPI revision의 지도 projection/publication이 비어 있었다.
- 운영 조치: 변경 전 PostgreSQL dump를 `/opt/onmaru/backups/onmaru-before-map-backfill-20261001.dump`에 보관하고 `pg_restore --list`를 확인했다. 원천 23,675건을 변경하지 않고 projection 23,675건 및 publication을 게시했다. `ANALYZE` 후 정보 목록·뷰포트 API가 200으로 응답했다.
- 코드: V037 idempotent backfill, 지역 코드가 없는 장소의 projection 게시, 통합 회귀 테스트, migration registry 및 schema 문서. Issue #553에 연결한다.
- 다음 단계: PR CI 확인 및 develop 병합. 사용자 브라우저 새로고침으로 지도 목록 표시를 최종 확인한다. 운영 서버에는 SQL이 직접 적용됐으며 다음 릴리스에서는 V037이 기존 publication을 확인하고 건너뛴다.

## 2026-10-01 Issue #545 온디맨드 스테이징

- 현재 브랜치: `feature/545-staging-deploy`. 사용자 결정: 기존 1GB/$7 Lightsail에 운영은 상시, 분리된 스테이징 Spring·PostGIS는 FE 개발자가 제한 SSH 명령으로 필요할 때만 실행하고 2시간 후 자동 중지한다.
- 완료: PR #546·#549·#550을 `develop`에 반영했다. 실서버에서 분리된 DB와 Flyway 35개 migration, 합성 장소, Spring health·CSRF·지도 조회를 검증하고 스테이징을 중지했다. 운영 컨테이너는 healthy다. Vercel Preview API URL·FE Preview 배포와 `develop` 브랜치에 묶인 `staging.onmaru.site` 별칭을 준비했다. GitHub `STAGING_SPRING_URL`은 새 API 주소로 변경했다. 상세 증적은 Issue #545에 기록했다.
- 이번 변경: CI `verify`·이미지 검사·migration gate 이후 검증된 `develop` 이미지 digest를 잠든 Lightsail 스테이징에 준비하는 수동 배포 job과 제한 SSH 배포 계정을 추가한다. FE의 `start`만 컨테이너를 실행한다.
- 다음 단계: 이 PR의 `verify` 확인·병합, CI 전용 공개키/비밀키 설정, 실제 수동 배포 검증. 사용자가 DNS는 나중에 추가하기로 해 API/FE 공개 TLS는 대기 중이다. FE 개발자 **공개키**와 Kakao staging callback, 로그인·쓰기·SSE 검증도 남아 있다. Issue #545는 열어 둔다.

## 2026-10-01 Issue #542 Toolkit 3회 정책 커밋 적용

- 브랜치: `fix/542-toolkit-three-run-pin`
- 범위: Module Benchmark caller의 reusable workflow `uses`와 `toolkit_ref`를 Toolkit PR #116 병합 커밋 `f3d5f4c244b42260a6b9ae9c1f7c76e6550f525a`로 함께 고정한다.
- 검증: caller 계약 테스트는 변경 전 통과했고, 새 SHA에 대한 실패를 재현한 뒤 통과했다. 전체 Node 테스트 127개와 `bash scripts/verify-contracts`가 통과했다. PR `verify`와 실제 Module Benchmark 실행을 확인한다.
- 남은 일: 현행 reusable workflow는 `develop` 모드에서 원본 증적을 모으지만 release 3회 비교 함수를 호출하지 않는다. 이 판정 연결은 [#543](https://github.com/YRootLab/OnMaru-backend/issues/543)에서 추적한다.

## 2026-10-01 Issue #509 관리자 목록 페이지네이션

- 브랜치: `feature/509-admin-pagination`; 구현 커밋 `e10057f`와 최신 `origin/develop` 병합 완료.
- 이번 세션: 빈·비정규 Base64 cursor 거부, 운영 queue 기본 limit 20, 회원 상태 필터 `ACTIVE|DELETING` 정정, cursor secret 시작 시 강도 검증, 좌표 누락 후기의 SQL 페이지 경계 수정, runtime 의존성 lockfile 보정, FE 연동 문서와 OpenAPI 정합성 보강.
- 추가 구현: 후기 `status + query`(본문·장소명) SQL 검색과 신고 `reason` SQL 필터를 cursor에 바인딩했다. 관리자 목록의 손상 cursor는 HTTP `400 VALIDATION_ERROR` 테스트로 확인하고, 후기·신고·dashboard·moderation queue의 전체 snapshot 미호출 경계 테스트를 추가했다.
- 검증: 병합 후 community 테스트, 관리자 cursor/API/dashboard·JDBC 후기/신고 대상 Spring 테스트, `bootJar`, `bash scripts/verify-contracts`, migration policy, `git diff --check` 통과. 앞선 전체 `check`는 453개 중 기존 map-info 3만 건 latency 테스트 1개가 ARM64의 amd64 Docker 에뮬레이션 환경에서 1085ms/500ms로 실패했다. 해당 테스트의 앞선 단독 재실행은 통과했다.
- PR: [#528](https://github.com/YRootLab/OnMaru-backend/pull/528)을 `develop` 대상으로 열었다. `Refs #509`로 연결했고, #509는 운영 유사 성능 및 배포 검증까지 열린 상태로 둔다.
- 다음 단계: PR `verify` 결과와 리뷰를 확인한 뒤 병합한다. staging 운영 유사 데이터 검증은 별도로 진행한다.
- 남은 운영 검증: staging 운영 유사 데이터의 `EXPLAIN (ANALYZE, BUFFERS)` 및 latency/heap 전후 비교, secret 설정과 배포 smoke.

## 현재 작업: Lightsail 배포 준비 (#519)

- 기준일: 2026-10-01
- 브랜치: `feature/519-lightsail-deployment` (branch parser 결과: #519)
- Git Flow: 기능 변경은 `develop` PR로 반영하고, 실제 운영 배포는 release 흐름을 거친 `master` 이미지 기준으로 한다. 이 PR은 배포 문서와 수동 배포 scaffold를 제공하며 #519를 닫지 않는다.
- 현재 GitHub 상태: 커밋 `73dd517`, `307dfe2`를 `origin/feature/519-lightsail-deployment`에 push했고 [PR #523](https://github.com/YRootLab/OnMaru-backend/pull/523)을 `develop` 대상으로 열었다. #519는 AWS 실배포가 남아 있어 `Refs`로 연결했고 계속 open 상태다. PR은 mergeable이며 CI가 아직 진행 중이므로 merge 전에 최신 `verify` 결과를 확인한다. CodeRabbit 자동 리뷰는 공개 저장소의 수동 리뷰 요구로 건너뛰었다.

### 이번 변경

- `infra/lightsail/`: Nginx/Spring/PostGIS Compose, 역할 분리 init/restore, DB backup, HTTP ACME bootstrap, HTTPS proxy, Certbot renewal timer, 비밀값 없는 `.env.example`, 실행 README를 준비했다.
- `docs/operations/runbooks/lightsail-render-cutover.md`: 현재 AWS 상태부터 DB 복원, HTTPS, FE 검증, 전환·rollback 및 Render 종료 조건까지 기록했다.
- Spring CORS를 환경변수 allowlist로 바꾸고 `/auth/csrf`에도 적용했다. CSRF 필터가 같은 Origin 정책을 사용하며 forwarded-header 처리 설정을 추가했다.
- `Dockerfile`의 JVM 메모리 제한을 컨테이너 메모리 기준 환경변수로 전달하도록 조정했다.
- 로컬 `.agents/`, `.claude/`, `skills-lock.json`은 기존 미추적 작업물이며 이번 PR에 포함하지 않는다.

### 검증 기록

- 기존 로컬 검증: Lightsail Compose config parse, Spring `:apps:spring-api:compileJava`, 셸 스크립트 syntax, YAML parse, `git diff --check` 통과.
- PR #523 CI의 Java/Module Benchmark 실패는 `AdmissionWebBoundaryTests`의 신뢰하지 않는 `X-Forwarded-For` 검사에서 재현했다. `server.forward-headers-strategy=framework`가 신뢰 프록시 검사 전에 원격 주소를 덮어쓴 것이 원인이다. 기본값을 `none`, Lightsail Compose를 `native`로 바꾸고 Nginx가 클라이언트의 `X-Forwarded-For`를 덮어쓰도록 수정했다. 대상 테스트는 수정 전 1건 실패, 수정 후 통과했다. 전체 unit/contract shard, Compose config parse, `git diff --check`도 통과했다. 커밋 `75f38d4`의 CI `verify`는 통과했다.
- 사용자 요청으로 일반 PR에서 Module Benchmark가 자동 실행되는 문제를 #524로 기록했다. `pull_request` 트리거를 제거하고 CI·테스트 관련 파일이 `develop`에 반영될 때만 자동 실행되도록 `push.paths`를 제한했다. 수동 실행은 유지했다. 진행 중이던 PR benchmark run `36796514768`은 취소 요청했다.
- runner 구조와 Java job 표시 개선은 #525로 기록했다. CI job 표시 이름을 `Java 모듈 및 Spring API 전체 테스트`로 구체화했다. 기존 모듈별 matrix 측정은 전체 workflow 소요 시간이 더 길었고, GitHub-hosted runner는 job마다 종료된다. 병렬 실행 방식은 사용자 선택 후 별도로 결정한다.
- 실제 Lightsail Nginx/TLS, Render DB 복원, FE 로그인·쿠키·CORS, 운영 트래픽 전환은 아직 검증하지 않았다.

### AWS 진행 상태 (2026-10-01 기록, deprecated)

> 아래 Lightsail bootstrap/migration 진행 기록은 과거 snapshot이다. 사용자 확인 기준 현재 운영 중이며 TourAPI 데이터가 적재된 원본은 AWS PostgreSQL이다. 아래 “DB 아직 없음/Neon 원본” 전제와 남은 작업 목록은 현재 상태로 사용하지 않는다.

- 인스턴스: `onmaru-prod-seoul`, Seoul `ap-northeast-2a`, Ubuntu 24.04, $7/월 번들(1 GB RAM, 2 vCPU, 40 GB SSD).
- 고정 IP 리소스: `onmaru-prod-seoul-ip` 연결 완료. 숫자 IP는 사용자가 콘솔에서 확인해야 하며 아직 DNS에 등록하지 않았다.
- 방화벽: TCP 80 공개, TCP 443 공개, TCP 22는 전체 IPv4/IPv6 및 Lightsail 브라우저 SSH로 열려 있다. 22 제한은 별도 로컬 SSH 경로를 확인한 뒤 production 전에 적용한다. 8080/5432는 열지 않는다.
- 인스턴스 패키지 업데이트 후 재부팅했다. 사용자가 브라우저 SSH 재접속, Docker/Compose 설치 및 Docker 서비스 `active` 확인, `/opt/onmaru` 생성과 `ubuntu` 소유권 설정을 완료했다.
- Docker 설치는 확인됐지만 `hello-world` 컨테이너 성공 여부는 아직 보고되지 않았다. 저장소/배포 파일, `.env`, 컨테이너, PostgreSQL 데이터, Nginx/TLS는 서버에 아직 없다.
- Automatic Snapshots는 비용을 고려해 꺼져 있다. PostgreSQL 별도 backup/restore 절차는 필수다.

### 다음 작업과 순서

1. PR #523의 `verify` CI와 PR diff를 확인하고, merge 전에 변경 사항을 검토한다.
2. PR 반영 뒤 GitHub workflow에서 사용할 이미지 SHA와 GHCR 접근 방식을 확인한다.
3. 서버에 배포 파일을 가져와 비밀값을 `.env`에 설정하고 `chmod 600`을 적용한다.
4. PostgreSQL/PostGIS만 먼저 시작하고 Render 원본 dump를 안전하게 복원·검증한다. 그 전에는 Spring을 시작하지 않는다.
5. Spring/Nginx를 구성하고 HTTP challenge로 TLS를 발급한 다음 HTTPS, health, CORS/CSRF, Kakao, SSE를 확인한다.
6. 그 뒤 `api.onmaru.site` DNS 및 FE Preview를 검증한다. 운영 전환 전까지 Render API/DB, Vercel `@`/`www`, Render Kakao callback을 유지한다.
7. 실제 운영 전환은 `develop` 직접 배포가 아니라 승인된 release 절차를 거친 `master` 이미지로 수행한다. DB 백업/복구 및 rollback 조건 확인 전 Render 자원을 종료하지 않는다.

## 남은 운영 위험

- Lightsail 한 대에 API와 DB가 함께 있어 호스트 장애 시 둘 다 중단되는 단일 장애 지점이다. 현재는 저비용 테스트/초기 운영 목표다.
- SSH 22 인바운드가 넓게 열려 있다. 접속 경로를 보존하며 source IP를 제한해야 한다.
- $7 번들은 1 GB RAM이라 Spring, PostgreSQL/PostGIS, Nginx 메모리 사용량을 관찰하고 OOM이 반복되면 상향을 검토한다.
- Render 데이터의 실제 DB 버전/용량과 dump/restore 결과, GHCR package visibility, 필요한 운영 secret은 아직 확인하지 않았다.
# 2026-10-04 Issue #603 검색 중심 스크린 속 한옥 리서치 하네스 설계

- 브랜치: `feature/603-screen-hanok-research-harness`.
- 운영 `스크린 속 한옥` 약 7개 항목을 재검증하고 고유 장소 100개·작품-장소 연결 150개 이상을 확보하는 검색 우선 내부 리서치 하네스를 설계했다.
- 기존 장소 category는 유지하고 작품-장소-복수 출처 관계만 별도로 누적한다. 검색 API가 evidence를 수집하며 LLM은 evidence 구조화·요약·애매한 매칭 판정에만 사용한다.
- TourAPI 변경 감지는 원천 수정 시각에 의존하지 않고 기존 revision의 `contentId + normalized SHA-256` set diff를 확장한다.
- 초기 backfill은 기존 active catalog에서 일일 예산으로 처리하고, 정상 운영은 TourAPI 증분 동기화와 작품 리서치를 14일 통합 run으로 실행한다. evidence URL은 30일 주기로 확인한다.
- 기존 장소는 목록 hash와 상세 hash를 분리한다. 목록이 변경된 장소만 즉시 상세 갱신하고, 변경 없는 장소는 중요도에 따라 30/60/180일 TTL로 quota 안에서 순환 재검증하며 기존 정상값을 보존한다.
- 설계 문서: `docs/superpowers/specs/2026-10-04-screen-hanok-research-harness-design.md`.
- 사용자 제공 TourAPI 신분류-관광타입 연계 XLSX를 원본 보존한 채 계층형 Markdown/JSON으로 변환했다. 전체 240개 소분류 행과 `contentTypeId` 연결을 옮겼다. OnMaru 적용 범위는 해당 Markdown에 기록: HS01·EX01·EX04·한옥스테이·VE04 일부 우선 후보, HS02/HS03/VE07/VE09/FD05/전통주/공예·시장/자연·랜드마크·산업관광 관련 항목은 선별 후보, C01 코스·EV 행사·대부분 LS 및 범용 업종은 초기 공개에서 제외한다. 중분류는 후보군, 소분류/장소 관련성은 세부 판정이며 작품 라벨은 별도 근거 기반이다.
- 사용자가 현재 운영 DB가 AWS PostgreSQL이며 TourAPI 데이터를 적재해 사용 중이라고 확인했다. Neon 운영 구성과 Neon을 현재 원본으로 취급하는 문서는 deprecated 처리 대상으로 분류한다. 중분류 선택 정책에 따른 실제 장소 건수는 AWS 활성 catalog revision에서 `lcls_systm2/lcls_systm3`별 distinct `contentid`를 read-only 집계해야 한다. 현재 실행 환경에는 AWS CLI/접속 세션이 없어 아직 SQL을 실행하지 않았다. 운영 DB 변경은 금지한다.
- 다음 단계: 사용자 문서 검토 후 구현 계획을 작성한다.

## 2026-10-04 Spring 운영 Blue-Green CD 준비 (#586)

- 브랜치/worktree: `feature/586-lightsail-blue-green-cd`, `.worktrees/feature-586-lightsail-blue-green-cd`. 사용자 요청에 따라 GitHub Issue에는 이번 내용을 작성하지 않았다.
- 기준: 최신 `origin/develop`에서 작업했다. `develop`은 자동 운영 배포하지 않으며, release 흐름을 거쳐 `master`에 반영된 Spring image만 운영 CD 대상으로 삼는다. FastAPI는 별도 Lightsail 배포 대상으로 남겨 두었다.
- 구현: Blue/Green Spring slot, Nginx runtime upstream, 1GB 호스트용 메모리·swap·OOM gate, 제한 SSH deployer, immutable digest 배포, 공개 smoke·70초 drain·비정상 종료 rollback, `master` 전용 production job을 추가했다. 스테이징이 실행 중이면 운영 배포와 rollback은 fail-closed로 중단한다.
- CD 보강: Repository Variable 활성화 gate, build 전 `status <sha>` no-op preflight, 성공 후 수동 rollback, 첫 전환의 legacy slot 복귀, 거부 SHA hold, 성공·실패·no-op webhook, 168시간 경과 dangling image 정리, 제3자 Action full commit SHA pinning을 추가했다. DB migration은 자동으로 되돌리지 않는다.
- PR 전 독립 리뷰 보강: runner image에 build SHA를 보존하고, traffic 전환 뒤 drain 전체 구간의 health·OOM·memory·swap을 재검사한다. 배포 상태는 generation directory와 `current` symlink의 단일 원자 교체로 확정하며, 이전 slot 중지 성공 뒤에만 commit한다. 실패 복구에서는 이전 slot health가 확인된 경우에만 route·env를 되돌려 정상 후보를 잘못 중지하지 않는다. webhook은 build와 migration 실패 단계도 구분한다.
- 시작 부하: Blue/Green 후보의 TourAPI·Odii `ApplicationReadyEvent` 동기화를 끌 수 있는 설정과 회귀 테스트를 추가했다. 정기 cron과 기존 DB lease/fence 정책은 유지한다.
- 실서버 리허설: 운영 route를 바꾸지 않고 동일 image의 Green을 384MB 제한으로 약 60초 겹쳤다. 약 30초 후 healthy, Green 약 212MB, swap 약 114MB 증가, 최저 `MemAvailable` 약 156MB, 기존 운영 health 200, OOM 없음이었다. 이는 무부하 중첩 증거이며 실제 새 image 전환이나 부하 상태 검증은 아니다. 리허설 컨테이너는 제거했고 운영은 healthy 상태를 확인했다.
- 블로그 초안: `docs/drafts/2026-10-04-lightsail-blue-green-cd.md`. Render+Neon에서 Lightsail로 옮긴 배경, Render 재활용의 DB/네트워크 문제, virtual memory와 thrashing, 측정 결과에 더해 GitHub variable 평가 시점, 두 종류 rollback, 예약 재배포를 막는 hold, image reference 기반 보존, Action supply-chain 경계를 서사로 정리했다. `blog-tone`과 `writing-rule`을 적용했다.
- 검증: 전체 Node 223/223, production/staging 배포 계약 14/14, startup-sync·Odii PostgreSQL 집중 Spring 테스트 `BUILD SUCCESSFUL`, Compose config, workflow YAML parse, production shell `sh -n`, 두 skill quick validation, 문서 계약, `git diff --check`가 통과했다. 현재 환경에 ShellCheck와 Actionlint 실행 파일이 없어 이번 보강 뒤에는 재실행하지 못했으며 PR의 CI `verify`를 최종 gate로 사용한다.
- 아직 활성화하지 않음: 운영 서버의 최초 `bootstrap-blue-green.sh`, production 전용 Ed25519 key 설치, Repository Variable `PRODUCTION_DEPLOY_ENABLED`, production environment의 `PRODUCTION_DEPLOY_SSH_KEY`/`PRODUCTION_DEPLOY_WEBHOOK_URL` secret과 `PRODUCTION_SSH_KNOWN_HOSTS` variable 설정, 실제 `master` 배포는 남아 있다. 첫 merge가 준비 없이 배포되지 않도록 활성화 flag 기본값은 false다.
- 배포 시점 변경: `master` push는 build·scan·migration 검증까지만 수행한다. 실제 운영 배포는 매일 `03:17 KST` schedule 또는 `deploy_production=true`인 명시적 `workflow_dispatch`에서만 실행한다. 예약·수동 실행은 먼저 server SHA·clean master·public health를 검사하고 같으면 build 전 no-op 처리한다.
- 서비스 범위: `master` 운영 경로는 Spring image만 build·scan·배포한다. FastAPI image 단계는 `develop` 스테이징 실행에만 두고, 별도 Lightsail CD 설계 전까지 운영 경로에서 제외한다.
- 프로젝트 skill: `skills/onmaru-production-deploy/SKILL.md`이며 로컬 discoverability용 사본은 `~/.codex/skills/onmaru-production-deploy`에 있다. “온마루/AWS/Lightsail 운영 배포해줘” 또는 `$onmaru-production-deploy` 직접 호출에서 `deploy.yml`을 `master`/`deploy_production=true`로 한 번 dispatch하고 결과를 관찰한다. `develop`의 release/master 승격은 사용자가 함께 명시했을 때만 선행한다. 설명·상태·스테이징 요청은 배포 권한으로 해석하지 않는다.
- Issue 상태: #586은 이번 CD 보강 외에도 SSH 22 `/32` 제한, 실제 backup 격리 restore, rollback 기간과 Render·Neon 정리 결과를 완료 기준으로 가지므로 이 PR에는 `Refs #586`을 사용하고 merge 뒤에도 해당 운영 증거가 생길 때까지 열어 둔다.
- release 전 staging 재검증에서 production preflight의 의도된 `skipped`가 간접 의존성으로 전파되어 migration/staging deploy까지 skip되는 현상을 재현했다. `migration-gate`가 image build 성공을 명시적으로 판정하도록 `always()` 조건을 추가하고 회귀 계약 테스트를 남겼다.
- 후속 Actions run `37178635461`에서 image build와 migration gate는 성공했지만 같은 skip 전파가 `staging-deploy`에도 남아 있음을 확인했다. staging deploy/smoke가 `always()`에서 직접 build·migration 성공을 판정하도록 보강하고 두 job의 회귀 계약을 추가했다.

## 2026-10-05 Issue #543 release module benchmark evidence

- 브랜치: `docs/543-release-benchmark-evidence`; 기준: PR #633 merge commit `648d3bd`가 반영된 최신 `origin/develop`.
- 범위: v0.3.38/v0.3.39의 `Module Benchmark`를 각각 서로 다른 3회 실행하고, 같은 `spring-api-postgres-other` module의 검토된 evidence를 Release asset으로 보존한 뒤 고정 Toolkit comparator로 release 판정을 재현한다.
- 관련 이슈: [#543](https://github.com/YRootLab/OnMaru-backend/issues/543). 완료 조건은 3+3 중앙값 비교, 15% 초과 회귀의 승인 보류, 원본 run/artifact link 보존, 단일 정본 판정 및 계약 테스트 통과다.
- 현재 증적: 여섯 실행이 모두 성공했다. v0.3.38 값은 467.05/341.36/394.40초, v0.3.39 값은 471.61/411.86/457.93초다. 중앙값 delta는 +16.108%로 `approval_hold`이며 자동 통과시키지 않는다.
- 상태: 두 Release asset과 v0.3.39 비교 asset 업로드, README/운영 보고서 반영, 정본 comparator 재실행과 계약 테스트 35개 통과. 이 문서 PR 병합 후 #543과 Toolkit #115를 종료한다.

## #716 catalog cutover DB compatibility (2026-10-12)
- Branch: `feature/716-selected-catalog-cutover`, PR #717.
- Cutover publisher no longer deletes prior KTO revision on publish or unchanged sync. Retention protects latest two KTO published revisions and KTO published revisions for 14 days, leaving an operational rollback window.
- `docs/operations/runbooks/selected-catalog-cutover.md` now audits removed public places referenced by saved places, reviews, and K-Contents, compares active map projections, and documents a transaction-safe active-pointer rollback. Do not claim prod cutover complete until affected reference counts and FE screen checks are reviewed; historical detail for removed places may require a separate compatibility migration.
- `./gradlew :apps:spring-api:test --tests '*JdbcTourApiCatalogPublisherTests' --tests '*RetentionCleanupJdbcTests'` compiled but Testcontainers initialization failed locally because Docker socket is unavailable. CI must run integration tests.

## #718 전수 1차 라벨링 (2026-10-12)
- Branch `feature/718-bulk-labeling`, Issue #718, PR #719. 최종 TourAPI 67필터/72페이지/14,936응답, 고유 11,083건 전수 1차 라벨을 `tmp/kcontents-718/`에 생성. 산출물은 Git 제외.
- 공식 촬영지 CSV 15,034행 SHA 확인, 정확명+30m 후보 217관계/99곳. 기존 검증 17곳은 옥천성당(contentId 2717818) 제목 검색 구제를 포함해 새 수집 범위에 모두 들어왔다.
- 운영 DB 적재, FE API 연결, 후보 관계 검수는 미완. 자세한 수치와 실행 명령은 `docs/operations/runbooks/full-candidate-labeling.md`.
- #718 V050은 버전형 내부 라벨 배치와 활성 pointer를 추가한다. `prepare-kcontent-label-import.py`는 기본 ROLLBACK, 건수·해시 검증 가져오기 SQL을 생성한다. V050은 아직 운영에 배포되지 않았고 11,083개 라벨은 로컬에 있다. 기존 검증 26관계/17곳은 전부 새 후보에 포함된다. Gemini는 공식 후보의 장면 요약 초안 210개만 생성했고 원문 인용 검사 후 전부 REVIEW_REQUIRED(7개 빈 요약), 공개 0.
- #717은 CI `verify` 통과 뒤 `develop`에 squash merge됐다(`ede5bb76`). 원래 stacked PR #719는 GitHub가 base 브랜치 삭제 때 자동 종료했으므로, #718 커밋을 `origin/develop` 위로 rebase하고 PR #720을 새로 열었다. #716 Issue는 운영 전환 완료 전까지 열어 둔다.
- #718 추가 근거 감사: 공식 드라마·영화·예능·아티스트 유효 좌표 15,028행과 11,083곳 전수 대조. 기존 검증 17, 이름 동일 100m 이내 248/1km 이내 82, 이름 일부 일치 78, 근접한 다른 장소 1,344, 자료 내 근접 근거 없음 9,308, 좌표 없음 6. `tmp/kcontents-718/evidence-audits.jsonl`과 `labels-reviewed.jsonl`에 출처 행·제목·미디어 유형·거리·상태를 보존한다. 감사 SHA `dc037b536d82ac82ea38c133cc5657ba7648ca57120358df7630c0d56d9ce6cb`. 외부 추가 조사나 인간 검증 완료를 뜻하지 않는다. V050과 가져오기 도구는 결정 집합·근거 감사 SHA를 함께 확인한다.

## AI RAG corpus 보존·GC 기획 (2026-10-20)

- 브랜치 `docs/776-ai-rag-prd`. 사용자 요청에 따라 Issue 추가 생성 없이 `docs/ai-rag/corpus-lifecycle-and-gc.md`에 활성 1개·롤백 최대 2개, 30일 export, 180일 manifest, 365일 비본문 감사 기록, 철회 우선 삭제, GC 경합·복원, 384차원 색인 실측 조건을 정리했다.
- `docs/ai-rag/README.md`, `rag-storage-schema.md`, `docs/ai/corpus-sync-contract.md`에서 새 정책을 연결했다. 실행 migration/DBML 및 GC job은 아직 구현 전이다. 당시 적은 `2GB 실측` 선행 조건은 2026-10-21 사용자 결정으로 폐기했고, 현행 1GB 혼합 부하·권리 검토·7일 백업 복원 실험을 먼저 수행한다.
- `bash scripts/verify-contracts` 통과. 로컬 Docker 기반 pgvector 환경 실험은 수행하지 않았다.
- 2026-10-21 설계 보완: `retrievalExpression`에 승인된 긍정/부정 검색 개념을 보존하고 원문 미전송 경계를 유지한다. BM25/vector 각 20개 검색 전후와 RRF 30개에서 장소·source 다양성을 제어하며, reranker 입력/동점/timeout/NaN 복귀와 후보 recall·장소 다양성 출시 gate를 문서화했다. 구현·모델 선정·실측은 남아 있다.
- 2026-10-21 후속 문서 정리: `docs/ai-rag/supervised-agents.md`를 추가해 온라인 결정적 RAG와 오프라인 원천 검수·검색 실패 진단·실험/운영 제안 agent를 분리했다. `docs/ai-rag/README.md`, `operations-and-evaluation.md`, `docs/ai/README.md`, 최초 `docs/planning` 설계 3개 문서의 정본 링크를 맞췄다. agent 실행 권한은 읽기/제안 저장에 한정하며 자동 색인 승격·운영 변경은 설계하지 않았다.
- 2026-10-21 AI RAG 문서 정합성 정리: `docs/planning/architecture-blueprint.md`, `data-api-design.md`, `docs/database/README.md`, `docs/database/azimutt/README.md`에서 새 홈 여정의 RAG를 optional로 보이게 하던 현행 설명을 정본에 맞게 수정했다. 당시 optional 검색 초안은 이후 중복 검토에서 제거하고, 과거 결정은 ADR-0007로 추적한다. `docs/ai-rag/README.md`의 관측 계약 링크를 보강했다. 상대 링크 0건 누락, ADR 16건, `bash scripts/verify-contracts`, `git diff --check` 통과. 코드·운영 인프라 변경 없음.
- 2026-10-21 사용자 확인: Grafana·Prometheus·OpenTelemetry를 이미 사용한다. `docs/ai-rag/observability-contract.md`에 기존 경로 재사용, RAG 단계별 trace·저카디널리티 metric·dashboard/alert·전송 유실/비용 검증을 설계했다. 운영 Compose의 OTLP metrics는 저장소 기준 `false`이고 AI RAG 계측은 미구현이므로 실제 송신과 Grafana 조회는 별도 검증이 필요하다.
- 2026-10-21 후속 설계 보강: reranker 500ms를 출시 합격선이 아닌 벤치마크 가정으로 낮추고 모델·후보 수별 품질/지연/메모리 실측 후 timeout을 결정한다. claim의 원천 offset/span·원자적 사실·시간/관계 검증과 오프라인 의미 판정기 평가를 추가했다. Spring OTel/Grafana 관측을 우선하고 Langfuse는 1GB 운영 서버 밖의 제한 pilot으로 검증한다.
- 2026-10-21 운영 준비 재검토: 사용자 판단에 따라 2GB 선행 이전을 철회하고 현행 1GB의 메모리·복구·혼합 부하 실측 후 필요 시 증설로 변경했다. `docs/ai-rag/production-readiness-gaps.md`에 출시 차단 증거와 미확정 항목을 정리하고, 온라인 run의 config/revision 고정·감독 trace 수집률·격리 runner·승격/rollback 계약을 보강했다. 실제 인프라·모델 성능 시험은 아직 미실행이다.
- 2026-10-21 RAG Quality Engineering 상세 설계: `docs/ai-rag/quality-engineering-agent.md`에 trace/평가 artifact, 다섯 책임의 상태 머신, 결정적 실패 탐지·Python 템플릿 보고서, 복합 원인에만 제한된 LLM 조사, 격리 반사실적 실험·도구 RBAC·비용·사람 승인·canary rollback을 정의했다. Naver API HUB는 오프라인 수집 후보로만, Langfuse/LangGraph/소형 오픈 모델은 별도 검증 후보로 두었다.
# 2026-10-21 AI RAG 브리핑·질문 처리 설계 추가

- 사용자 요청에 따라 `docs/ai-rag/reports/2026-10-21-system-briefing.html`에 사용자 경험, 데이터 준비, 질의별 SQL/BM25/vector/hybrid 계획, 온라인 응답, 근거 검증, 품질 감독, 2GB 운영 준비도를 종합했다.
- `docs/ai-rag/query-processing.md`를 새로 두었다. 첫 출시의 정형 SQL·본문 hybrid 기본값과, 평가·사람 승인 뒤 단일 검색 경로를 활성화하는 조건을 구분한다. 외부 Wiki/API는 권리·신선도·철회 계약 전에는 온라인 검색에 넣지 않는다.
- HTML 상대 링크 27개와 내부 anchor를 검사했고 누락 0건이다. `git diff --check` 및 ADR 16개 validate 통과. HTML은 목표 설계 보고서이며 RAG 구현·AWS 이전은 여전히 미실행이다.
- 사용자 후속 요청으로 HTML을 요약에서 전체 설계 보고서로 확장했다. `docs/ai-rag/reports/build_system_briefing.py`가 23개 Markdown 원문을 HTML 내부 절로 묶고 문서 간 링크를 내부 anchor로 고친다. 문서별 원본 링크·본문 검색·접힘/펼침을 제공한다. 생성 두 번의 SHA-256 일치, 내부/상대 링크 235개 누락 0, ID 중복 0, JS 문법 검사, HTTP 200, `git diff --check`를 확인했다.
- 2026-10-21 레이어 재설계: `docs/ai-rag/module-boundaries-and-pipelines.md`에 오프라인 Corpus 준비·온라인 응답·비동기 품질 감독의 논리 모듈, 소유자, 입력/출력 artifact, 실패·버전·권한 경계와 구현 순서를 상세화했다. `architecture.md`의 단축 도식을 normalization→planning→routing과 승인 검색 mode 분기로 교체하고 README·요청/SSE·OTel 계약을 맞췄다.
- HTML 보고서는 Mermaid CLI로 원본 다이어그램을 로컬 SVG로 렌더링하고 앞부분에 4개 레이어 확대 그림을 추가했다. AI-RAG 목표·ADR·runbook·공통/현행 AI 계약 26개 본문을 포함한다. 중복 요약 `docs/ai-rag/supervised-agents.md`는 상세 품질 감독 정본으로 참조를 옮긴 뒤 제거했다. `docs/ai/`의 기존 proposal 관련 문서는 현행 공개 계약이 참조하므로 전환 참고자료로 유지한다.

## 2026-10-22 AI 저장소 분리·관측 결정

- **2026-10-22 실제 문서 이전:** AI-RAG 설계, diagrams, HTML report를 `YRootLab/onmaru-ai-engine` 기본 브랜치에 이전했다. 현재 정본은 [AI Engine docs/ai-rag](https://github.com/YRootLab/onmaru-ai-engine/tree/main/docs/ai-rag)다. 백엔드 `docs/ai-rag/`에는 정본 안내만 남기고 외부 참조를 AI 저장소 commit으로 연결했다. 원격 AI 저장소의 `main`에는 이전 문서가 있으며 runtime 코드/독립 CI/배포는 아직 BE의 `ai/`에 남아 있다.
- **2026-10-22 보고서 빌더 분리:** `onmaru-ai-engine`의 `build_system_briefing.py`가 AI 소유 문서 21개만 읽도록 수정했다. Spring·ADR·DB·Lightsail 문서를 파일시스템 상대경로로 복사/번들하지 않고 소유 저장소 링크로 참조한다. ownership 회귀 테스트 추가, 보고서 재생성, Markdown 상대 링크 검사와 unit test 통과.
- **단일 Lightsail 목표 반영:** 저장소 이전이 새 AWS 인스턴스 생성을 뜻하지 않는다. `onmaru-ai-engine`은 immutable FastAPI image를 발행하고, backend 저장소가 현재 Lightsail Compose/deployer에서 Spring·PostgreSQL·Nginx와 같은 host에 배포할 계획이다. 기존 production compose는 FastAPI를 아직 포함하지 않으며 2GB 혼합부하/rollback gate 미통과 상태다. Spring 측 구현 계약은 `docs/ai-rag-spring/repository-and-deployment.md`에 작성했다.
- **후속 실행:** 현재 BE worktree에는 광범위한 미커밋 문서/DB 변경이 있으므로 선택 파일만 검토·commit할 것. 다음 runtime migration에서 임시 FastAPI baseline을 AI 저장소로 옮기고 독립 CI/image를 먼저 만든 뒤, BE의 ai/ path·CI/deploy workflow를 제거/연결한다. 그다음 Compose AI service와 제한 deployer의 digest/rollback 지원을 구현한다. 실제 Lightsail 변경·배포는 이 작업에서 수행하지 않는다.
- `onmaru-ai-engine` 공개 빈 저장소를 생성했다. 현재 AI 코드·CI·배포 경로는 BE 저장소의 `ai/`를 사용한다. 코드와 문서 이전은 아직 실행하지 않았다.
- `docs/ai-rag/repository-split-and-monitoring.md`에 BE의 제품·서비스 간 계약과 AI의 내부 구현 문서 정본, BE `docs/contracts/`의 내부 wire 계약, 독립 CI·이미지 digest·호환/rollback 검증을 선행하는 이전 순서를 고정했다.
- 관측은 Grafana Cloud 통합 조회·경보, 기존 Prometheus 호환 metrics 경로 검증 후 단일 전송, Spring/FastAPI 직접 OTLP trace, 기존 구조화 로그, 영속 품질 artifact로 결정했다. 2GB 호스트에 Tempo·Loki·Langfuse를 상주시켜 시작하지 않는다.
- 실제 계측의 차단 항목: Spring의 현재 all-zero `traceparent` parent ID와 FastAPI의 문자열 기반 correlation을 W3C OTel context 전파로 교체해야 한다. 운영 metrics 수집 경로와 Grafana Cloud traces 조회·사용량은 staging에서 확인해야 한다. 이 작업 전 통합 trace 완료를 주장하지 않는다.
- 다음 단계는 현 BE 설계 commit을 고정한 뒤 AI 레포에 FastAPI 코드·AI 소유 문서·독립 CI를 옮기고, BE 링크·CI·배포·계약 fixture를 같은 전환 작업에서 검증하는 것이다. 전환 검증 뒤에만 BE `ai/`를 제거한다.
- 2026-10-22 관측 후속: Claude Code 공식 OTel 문서와 NousResearch Hermes Agent의 공개 관측 코드/계약을 검토해 `repository-split-and-monitoring.md`에 요청 trace, 운영 metric, 재현 artifact의 역할을 명확히 했다. `observability-contract.md`에는 `traceId`와 영속 `runId`의 관계, provider 시도·SSE 재접속·비동기 작업·sampling/전송 실패 조사 경로를 추가했다. 실제 계측이나 Grafana 실측 완료를 뜻하지 않는다.
- 2026-10-22 스키마 후속: `docs/ai-rag/telemetry-and-harness-schema.md`와 `schemas/run-observation.v1.schema.json`에 단계 enum·결과/오류/복귀 코드, Spring 단일 writer의 영속 관측 artifact, trace 유실·품질 하네스 자동 분류/사람 승인 경계를 고정했다. HTML 보고서에 새 문서를 포함했다. 구현·실제 artifact 발행은 아직 하지 않았다.
- 2026-10-22 라우팅 결정: 사용자 요청에 따라 첫 AI RAG 출시부터 `SQL_ONLY`, `LEXICAL_ONLY`, `DENSE_ONLY`, `HYBRID`, `SQL_PLUS_RETRIEVAL`을 모두 활성화하도록 PRD·질문 처리·아키텍처·요청 생명주기·모듈 경계·평가·운영 준비·ADR-0017을 갱신했다. 각 route의 gold/오라우팅·2GB 부하 검증을 전체 출시 게이트로 두며 온라인 외부 Wiki/API는 여전히 미포함이다. 구현과 실측은 미완이다.
- 2026-10-22 후속 설계: 기존 16차원/메모리 내 검색은 의도적인 임시 baseline으로 기록하고 목표 RAG의 결함으로 평가하지 않는다. 실측 수치는 구현·관측 후 축적한다. 첫 출시 평가 계획은 5개 route별 최소 300문항(개발 200/봉인 100)으로 넓히고 부정·다턴·권리·주입·개인정보·되묻기 사례를 중첩 태그로 설계했다.
- Spring 질문 처리에서 허용된 원문 표현의 수식·어순·부정 범위를 비식별 `semanticQuery`에 보존해 FastAPI 로컬 BM25/embedding/reranker에 전달한다. provider·로그·trace에는 보내지 않고 핵심 의미나 비식별화가 불확실하면 한 가지 확인 질문으로 전환한다. 사용자에게는 조건이 충분하면 바로 답하고, 추천/수정/해설/비교/콘텐츠 연결 prompt와 검증된 3~5 장소 카드·문화 해설·이미지·출처·후속 chip을 제공하는 목표 계약을 `docs/ai-rag/conversation-and-prompt-design.md`에 추가했다. 구현은 아직 아니다.
- 2026-10-22 HTML 브리핑 후속: 보고서 앞부분의 오래된 “BM25/vector 단독은 추후 활성화” 설명을 첫 출시 다섯 경로 계약으로 교정하고, 전체 세 파이프라인 지도와 합성 질의 → 단계별 Recall@K/MRR/nDCG → 실패 분류 → 검토 큐 → 운영자 실험/사람 승인 흐름을 추가했다. `evaluation-and-environment-tests.md`에는 공개 source 기반 합성 질문 제작, 독립 라벨링, split 누설 방지, 고정 version 절차를 명시했다. 보고서 29개 원문 포함, 내부/로컬 링크 누락 0, 중복 ID 0, 생성 2회 SHA 일치, `git diff --check` 통과. 코드는 변경하지 않았으며 평가 질문·실측은 아직 생성되지 않았다.
- 2026-10-22 AI route 소유권 변경: 사용자의 명시 결정으로 `onmaru-ai-engine`이 비식별 `safeQuery`와 확정 구조화 맥락을 해석해 `PlanProposal`/route를 만든다. Spring은 회원·원문·공개 범위/필터·정형 SQL·최종 답변을 소유하고 계획을 승인한다. FastAPI plan 호출→Spring authority gate/SQL→필요 시 FastAPI execute의 두 단계 내부 계약, SQL_ONLY의 plan-only 동작, transient `semanticQuery`·planHash, OTel 단계와 RunObservation schema를 `docs/ai-rag/`와 ADR-0017에 반영했다. HTML 브리핑 29개 문서 재생성, 내부 링크/ID·JSON·`git diff --check` 검증 통과. 목표 설계이며 런타임 구현/계약 fixture는 후속 작업이다.
- 2026-10-22 회원 대화 보존 결정 변경: 이전 6개월 자동 삭제 정책을 폐기하고 회원이 직접 삭제하거나 탈퇴할 때까지 대화·찜을 유지하는 목표 설계로 PRD·보안/보존·API/모델·모듈 경계·README·HTML 브리핑을 갱신했다. `expiresAt` 목표 응답 필드를 제거했다. 백업 최대 30일은 초기 제안이며 삭제 원장 재적용과 오래된 장소/출처의 현재 공개 자격 재확인은 유지한다. 실제 migration·개인정보 고지·삭제/복원 검증은 구현 전이다. HTML 내부 링크/ID·`git diff --check` 통과.
- 2026-10-22 비개발자용 시각화 후속: `docs/ai-rag/reports/2026-10-21-system-briefing.html` 첫머리에 Mermaid 없는 5단계 사용자 여정(질문→이해→근거 검색→카드→이어가기), 단계별 접이식 내부 동작, 모호성/근거 부족 분기를 추가했다. 보고서의 단독 lexical/dense 경로가 배포 범위 밖이라는 오래된 문장을 첫 출시 범위로 바로잡고 README에 비개발자 브리핑 순서를 적었다. 29개 본문 포함, HTML 링크/중복 ID 0, 생성 두 번 SHA 일치, `git diff --check` 통과. 브라우저 육안 검사는 별도로 하지 않았다.
- 2026-10-22 모듈별 시각화 재작업: 사용자의 교정에 따라 비개발자용 5단계 화면 스토리를 HTML 보고서에서 제거하고, `reports/module-flow.fragment.html`의 비 Mermaid 스윔레인으로 교체했다. 온라인 9단계는 Spring·AI 엔진·PostgreSQL·Gemini·Next.js 레이어의 입력/출력·조건부 실행을 보여 준다. Corpus 준비·비동기 품질 감독 모듈 체인을 같은 절에 넣고 README 안내를 변경했다. 보고서 29개 원문, 로컬/내부 링크 누락 0, 중복 ID 0, 두 번 생성 SHA 일치, `git diff --check` 통과. 브라우저 자동화 환경에는 브라우저가 없어 육안 검사는 못 했다.
- 2026-10-22 구조도 정교화: Graphviz 자동 배치의 교차선과 화면 잘림을 해결하기 위해 `reports/diagrams/build_ai_engine_architecture.py`의 고정 배치 SVG로 교체했다. 클라이언트·AWS Lightsail 내부 Spring/FastAPI/PostgreSQL·외부 Gemini 경계를 분리하고, Spring 최종 검증→SSE→클라이언트 반환, SQL_ONLY 정형 경로, corpus 색인, 비동기 평가를 표시했다. HTML 이미지는 화면 너비에 맞춰 전체를 보이고 클릭하면 SVG 원본을 확대한다. PNG 렌더를 육안 확인했고, 보고서 두 번 생성 SHA 일치·내부/로컬 링크 누락 0·중복 ID 0·`git diff --check` 통과. 브라우저 실화면 검사는 미실행이다.
- 2026-10-22 C4 구조도 상세화: `docs/ai-rag/diagrams/likec4/architecture.c4`에 OnMaru 시스템 경계, FE·Spring·FastAPI·PostgreSQL 컨테이너, 두 서버 내부 컴포넌트, Vercel/AWS 목표 배포, 사용자 온라인 plan→승인 실행→검증 SSE 시퀀스, 오프라인 corpus·quality harness 시퀀스를 모델링했다. C4-PlantUML 정적 도식 4장(컨테이너·배포·Spring·FastAPI)을 보고서에 추가했고 LikeC4 인터랙티브 사이트 링크를 걸었다. 목표 설계와 현재 미배포 상태를 명시했다. LikeC4 validation 0 errors, PlantUML SVG 4장 재생성, 보고서 29개 문서 포함 및 2회 SHA 동일, 내부/로컬 asset 누락 0·ID 중복 0·`git diff --check` 통과. C4 FastAPI 렌더를 PNG로 변환해 육안 확인했다.
- 2026-10-22 질문 처리 네이밍 결정 반영: 상위 계층은 `Query Processing Layer`, 내부 단계는 `Query Preprocessing → Query Interpretation → Search Router`로 정리했다. 구현 검색기는 `Keyword Search (BM25)`와 `Semantic Search (Vector)`, 결합 단계는 `Hybrid Fusion (RRF)` 및 별도의 `Reranker`로 이름을 통일했다. 모듈 경계·질문 처리·요청/SSE·관측 span/schema·README·ADR 외 연관 설명·LikeC4/C4-PlantUML·HTML 정적 설명을 맞췄다. `LEXICAL_ONLY`/`DENSE_ONLY`는 계획 내부 enum으로 유지하고 설명명과 구분했다. LikeC4 validation 0 errors, PlantUML 렌더 4장 통과, HTML 29개 문서 포함·생성 안정성·ID/링크 검증 및 `git diff --check` 통과. 커스텀 메인 SVG와 FastAPI C4 SVG를 PNG 변환해 육안 확인했다.
