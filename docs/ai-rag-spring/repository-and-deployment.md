# AI 엔진 저장소와 Lightsail 배포 통합

> 상태: AI 설계 문서와 기존 FastAPI baseline 코드는 `onmaru-ai-engine` 저장소 `main`에 있다. GitHub Actions에서 테스트·image build·Trivy scan 및 immutable digest 발행까지 통과했다. 이 저장소에는 private Compose service와 digest 기반 수동 AI 배포 경로를 추가했다. Lightsail 운영 활성화와 2GB staging 혼합부하 검증은 아직 하지 않았다.

## 결정된 배포 형태

AI 엔진 코드를 별도 Git 저장소로 관리하되, 초기 운영은 기존 AWS Lightsail 인스턴스 한 대에서 Spring Boot·PostgreSQL/PostGIS/pgvector·FastAPI·Nginx를 함께 실행한다. 저장소를 나누는 일은 서버를 나누는 일이 아니다. 별도 Lightsail, 상시 worker, 새 공개 포트는 추가하지 않는다.

`onmaru-ai-engine`은 Python 코드·테스트·AI CI와 immutable FastAPI OCI image를 소유한다. `OnMaru-backend`는 운영 Compose, Nginx, PostgreSQL volume/backup, deployer와 release pairing을 소유한다. GHCR의 정확한 AI image digest를 제한된 배포 입력으로 전달해 같은 인스턴스의 Compose service를 갱신한다. SSH credential, 운영 `.env`, DB volume과 provider secret은 어떤 AI 저장소에도 복사하지 않는다.

현재 운영 host는 1GB이며, 기존 Lightsail의 2GB 플랜 업그레이드 후에 AI 컨테이너 공존을 검증한다. deployer는 `ONMARU_AI_ENABLED=true`와 최소 약 1.7GB의 MemTotal을 요구한다. 업그레이드 전까지 AI 배포가 거부된다. 2GB에서도 Spring, PostgreSQL, AI 모델, reranker와 인덱싱의 RSS·CPU·swap·지연을 staging에서 측정해 공존을 통과해야 한다.

## 구성과 네트워크

```text
Internet
   │ HTTPS 443
Nginx (existing Lightsail Compose)
   ├── public REST/SSE ──> Spring Blue OR Green
   └── no public route ──> FastAPI ai-engine (private only)
                              ├── authenticated private request <── Spring
                              └── private PostgreSQL connection ──> PostgreSQL
                                                                    └── ai schema
```

- 호스트에 공개되는 포트는 기존 Nginx의 80/443뿐이다. Spring 8080, FastAPI, PostgreSQL 5432는 publish하지 않는다.
- FastAPI는 `app` network에서 Spring과 내부 요청을 주고받고, 필요한 경우에만 PostgreSQL `database` network에 붙는다. DB는 `ai` 전용 runtime role과 schema 권한을 제공한다. 임의 SQL 실행이나 Spring 업무 schema 쓰기를 허용하지 않는다.
- 내부 호출은 우선 기존 private REST와 현재 인증 계약을 사용한다. MCP는 정해진 서비스 간 orchestration의 대체가 아니며, discovery가 필요한 도구에 한정한다. gRPC는 trace와 부하 측정에서 실제 병목이 발견된 뒤 동일 업무 계약으로 실험한다.
- AI trace는 W3C `traceparent`를 Spring→FastAPI로 전파해 기존 OTel/Grafana 흐름을 잇는다. Prometheus metric scrape와 Nginx SSE 설정은 현재 구성을 우선 재사용한다.

## 이미지 발행과 운영 승격

1. AI 저장소 PR에서 unit/contract/evaluation test, dependency/build 검증 후 image를 빌드·취약점 검사하고 GHCR에 SHA tag와 digest를 발행한다. PR용 임의 이미지를 운영 credential이나 production environment에 노출하지 않는다.
2. 백엔드 staging에서 Spring image와 AI digest의 호환 조합, schema migration, readiness, 내부 인증, trace 연속성을 검증한다. DB schema 계약 version과 API `contractVersion`을 확인한다.
3. 배포 요청은 Spring commit/digest와 AI digest를 모두 고정한다. Spring만 바뀌는 release는 현재 AI digest를 그대로 재사용한다. AI만 바뀌는 release는 현재 Spring digest를 고정해 조합을 검사한다.
4. 기존 Spring Blue-Green 전환은 유지한다. 초기 FastAPI는 메모리 중복을 피하기 위해 단일 컨테이너만 실행한다. AI 교체 중 Spring은 검색 fallback/명확한 degraded response로 동작하며 제품 전체 요청을 막지 않는다.
5. 새 AI 컨테이너가 readiness 및 내부 smoke를 통과하지 않으면 활성화하지 않고 직전 digest로 복구한다. AI 컨테이너 두 개의 동시 기동은 메모리 한도를 측정하고 별도 승인된 뒤에만 허용한다.
6. DB migration은 일반 런타임 시작 시 자동 수행하지 않는다. 별도 migration identity/작업으로 실행하고 이전 이미지가 호환되는 순방향 migration인지 확인한다. rollback은 가능한 경우 앱 digest 복구로 처리하고 destructive schema rollback은 금지한다.

## 현재 배포 구현과의 차이

[`infra/lightsail/compose.yaml`](../../infra/lightsail/compose.yaml)은 Spring Blue/Green, PostgreSQL, Nginx와 `ai-engine` profile을 정의한다. AI service는 Spring과 같은 내부 `app` 네트워크에만 연결되고 host port를 공개하지 않는다. Spring의 내부 AI URL도 `http://ai-engine:8000`으로 지정한다. `infra/lightsail/production/deploy-ai-engine.sh`와 `deploy-ai` 제한 SSH 명령은 AI SHA가 아닌 immutable digest만 허용하고, health 실패 시 직전 image로 되돌린다. `.env`의 AI feature switch가 꺼져 있거나 host 메모리가 기준보다 낮으면 거부한다. 운영에서 이를 실행한 것은 아니며 현재 staging/운영 배포 상태도 아니다.

구현 시 백엔드 저장소에서 변경할 항목:

- AI runtime이 PostgreSQL을 아직 직접 사용하지 않으므로 현재 profile은 DB network에 연결하지 않는다. 향후 DB를 사용할 때 전용 role/schema 설계와 migrations/backup 검증을 먼저 추가한다.
- 2GB 전체 메모리 예산을 다시 측정한다. 기존 Spring Blue-Green 중첩은 유지하되 FastAPI 후보 중첩은 비활성화한다. API 상한을 정하기 전 RSS/CPU/swap 실측을 보존한다.
- 현재 AI-only digest 배포 scaffolding은 구현했으며 Spring+AI 결합 release pairing과 운영 audit/rollback exercise는 남아 있다. 임의 image name, mutable tag, 셸 명령은 입력받지 않는다.
- PostgreSQL image에 PostGIS와 pgvector가 함께 들어가는지 검증하고, 운영 volume을 보존한 상태로 extension migration·권한·backup/restore를 시험한다.
- 기존 `deploy.yml`, staging compose, rollback/status/health smoke, secret runbook, CI path filters에서 `ai/` 경로 참조를 inventory 후 이전한다. 먼저 AI image를 build·scan·staging 실행하고, 운영은 별도 enable gate가 통과한 뒤 연다.
- SSE/기존 public route를 Nginx에서 유지하고 FastAPI와 PostgreSQL 포트를 호스트에 publish하지 않는 compose test를 추가한다.

## 공존 검증 게이트

2GB staging host에서 production과 같은 Spring·DB·Nginx 이미지 조합 및 대표 AI 모델/코퍼스 크기를 사용해 다음을 확인한다.

- 정상 질의·동시 질의·인덱싱 중 MemAvailable, 각 컨테이너 RSS, CPU, swap, disk/index 증가량
- Spring Blue-Green 배포 중 AI가 실행될 때의 peak memory와 OOM 여부
- AI 컨테이너 재시작/오류 시 Spring의 timeout·fallback, SSE 종료/재연결과 사용자 오류
- DB 권한 위반, AI 전용 schema migration, pgvector/BM25 검색, backup 복원
- Spring→FastAPI traceparent·span parent 관계와 배포 digest 기록
- 실제 운영에 투입할 embedding/reranker 모델이 상시 메모리를 점유하는 경우와 CPU 제한 환경

합격 여부는 현재 CPU 사용률만으로 결정하지 않는다. 인덱싱 및 peak 동시 부하의 메모리/지연과 기존 서비스 회귀를 함께 측정한다. 게이트가 미통과면 AI 배포를 열지 않고 동일 인스턴스에서 CPU concurrency·model loading·작업 스케줄을 조정한 뒤 재시험한다.
