# OnMaru 백엔드 설계 보고서

> **2026-09-11 개선 설계:** [감사 후속 계약](revision-2026-09-11/README.md)이 최신 검토 기준이다. 모듈/DB·소유권·run 복구·방문 후기·검색·자원 정책은 해당 묶음을 우선한다. 구조 ADR은 초안 승인 대기이며 구현 완료를 뜻하지 않는다. 아래 장기 SSE/RAG 및 1.0 예시는 최신 MVP 계약과 구분한다.


작성일: 2026-09-09. 상태: 검토용 제안 v0.1. 애플리케이션 구현이나 배포 완료를 의미하지 않는다.

사용자가 정한 방향은 Java/Spring Boot 비즈니스 서버, Python/FastAPI AI 서버, 이슈 기반 개발이다. 나머지 선택은 아래 근거와 검증 조건을 갖춘 제안이다. 출시 시점·인원·운영 예산은 아직 미정이다.

## 읽는 순서

**추가 요청 반영:** [이야기길 확장 PRD·FE 감사·추천/AI·SSE·공모전 평가](journey-exploration/README.md). 주간 TOP5, 게시형 큐레이션, 실제 온기 API, 선택 보존형 탐색을 다룬다. 아래 최초 계획과 다른 부분은 확장안에서 변경 이유와 검토 상태를 명시했다.

1. [Architecture Blueprint](architecture-blueprint.md): 요청한 15개 항목, 35개 질문, 평가표.
2. [브레인스토밍과 백엔드 PRD](backend-prd.md): 대안 발산/수렴, 사용자 기능, 출시 단계, 인수 기준.
3. [데이터·API·RAG 상세 설계](data-api-design.md): 데이터 소유권, 관계, API 전환, 데이터 정합성.
4. [실행 계획](issue-plan.md): Root/Child 초안, 선행 관계, 통합 게이트.
5. [Work Graph](work-graph.json): spec-to-issues 검증 스크립트 입력.
6. [ADR 검토 초안](adr-proposals.md): 결정별 선택 이유, 비용, 재검토 조건. 정식 ADR 등록 전 검토 자료.

PRD는 **무엇을, 누구를 위해, 어디까지 만들고 어떻게 검증할지**를 정의한다. Blueprint는 **어떤 경계와 의존성으로 구현할지**, ADR은 **대안 중 왜 그 선택을 했는지**, Issue는 **누가 독립적으로 구현·검증할 작업인지**를 정의한다.

`work-graph.json`은 W0~W11과 X0~X8을 통합한 21개 후보 DAG다. [통합 발행 초안](implementation-issues.md)이 제목·선행 관계·wave·인수 조건과 기존 W 계획의 변경점을 제공한다. GitHub에는 아직 발행하지 않았다. JX-12는 제외하고 X7은 선택 P2로 분리한다. [P0 정리·ADR 검토](p0-triage.md)에 미해결 입력과 재현성 한계를 기록했다.

## 출시 입력 상태

| 입력 | 상태 |
|---|---|
| 참가 부문 | 2026 웹·앱 개발 부문 가정, 사용자 확인 대기 |
| 제출 마감 | 미정 |
| 심사 강조점 | 기존 competition-review 제안, 부문 확인 후 적용 |
| 팀 인원/가용 시간 | 미정 |
| 운영/모델 예산 | 미정 |
| 데모 목표/지역 | 이야기길 핵심 동선 제안, 확정 대기 |
| 두 참고 자료 | URL/로컬 경로 수령 대기 |

확정된 런타임 역할은 [ADR-0002](../decisions/0002-spring-business-fastapi-ai.md)에 기록했다. 그 밖의 아키텍처 제안은 자동 승인되지 않는다.

## 근거와 한계

| 근거 | 확인 내용 | 해석 수준 |
|---|---|---|
| [FE 추적표](../specs/traceability/fe-be-traceability-matrix.md) | 한옥, 지도, 온기, 오디, 도슨트 BE-REQ-001~009 | 요구사항 후보 |
| [FE 계약](../specs/data-contracts/place-canonical.md), [백엔드 요구](../specs/backend-requirements/persistence-entity-model.md) | 장소 통합과 DB 후보 | 제안이며 확정 DDL 아님 |
| [스키마 가이드](../backend_schema_design_guide.md) | 체크인/H3, 정-길, 북마크, 건축 데이터 확장 | 초기 기획 계보 |
| [기존 DB](../database/schema.md) | Odii 복합 원본 식별자 | 기존 모델 근거 |
| [관광 API 분석](../api/tour/tour_korean_info_api.md), [Odii](../api/tour/tour_audio_guide_api.md) | 원본 필드, 동기화, 분류 | 저장된 분석·샘플, 이번 세션 실 API 호출 아님 |
| [방문자 분석](../api/tour/tour_ongi_mode_visitor_api.md) | 일별 지연 집계 | 실시간 장소 인원으로 해석 금지 |
| [행안부](../api/mois/mois_hanok_api_review.md), [정-길](../api/tour/tour_jeonggil_api.md) | 이미지 부재, 좌표/매칭 과제 | 외부 서비스 기능·이용 조건 추가 확인 필요 |

FE 소스 중 `src/hanok/services/hanokArchive.service.ts`, `src/map/warmth/warmthRepo.ts`, `src/features/odii-audio/utils/scriptParser.ts`를 직접 확인했다. 각각 현재 분류 코드, localStorage 저장, 균등 시간 배분 자막을 확인했다. FE 전체 코드 동작을 실행 검증한 것은 아니다.

초기 PRD는 `/Users/yangseunghyeon/Development/OnMaru/OnMaru-docs/prd.md` v2.5를 참고했다. 최신 FE는 원래 기획의 단순 상위집합이 아니다. 체크인·정-길처럼 초기 PRD에는 있지만 현재 추적표에 없는 기능과, 도슨트 Q&A처럼 별도 상세화된 기능을 구분했다.

현재 `docs/specs`와 스키마 가이드는 로컬 절대경로 심볼릭 링크다. CI와 다른 개발자의 checkout에서 원본을 읽을 수 없다. W0에서 upstream commit과 콘텐츠 해시를 기록한 저장소 내부 스냅샷 또는 재현 가능한 가져오기 방식을 확정한다. 이 문서는 원본 링크를 따라 수정하지 않는다.

## 기술 근거

2026-09-09 공식 문서 확인. 버전은 실제 scaffold 시 호환성 테스트 후 고정한다.

- [Spring Boot 시스템 요구사항](https://docs.spring.io/spring-boot/system-requirements.html): 조회 시 4.1.1, Java 17~26, Gradle 8.14 이상 8.x 또는 9.x. Java 21 + Boot 4.1 계열을 검증 시작점으로 제안한다.
- [Spring 트랜잭션](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html): 프록시 호출 경계와 self-invocation 제약.
- [Gradle 멀티 프로젝트](https://docs.gradle.org/current/userguide/multi_project_builds.html): 프로젝트 의존성으로 컴파일 경계 구성.
- [ArchUnit](https://www.archunit.org/userguide/html/000_Index.html): 패키지 접근과 순환을 테스트로 검증.
- [Spring Modulith 검증](https://docs.spring.io/spring-modulith/reference/verification.html): 선택적인 모듈 구조 검증 도구.
- [PostGIS ST_DWithin](https://postgis.net/docs/ST_DWithin.html): geography 반경 단위는 미터.
- [pgvector](https://github.com/pgvector/pgvector): exact/approximate 검색과 필터링의 회수율 trade-off.
- [FastAPI lifespan](https://fastapi.tiangolo.com/advanced/events/): 프로세스 내 클라이언트·모델 리소스 수명 관리.

보고서의 성능 수치와 비용 상한은 실측 결과가 아니라 부하 테스트 및 운영 협의에 사용할 초기 제안이다. 공급자 이용 권한, 실제 데이터 신선도, 라이브 API 버전·쿼터는 W0의 확인 대상이다.
