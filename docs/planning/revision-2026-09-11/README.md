# 감사 이후 설계를 실행 계약으로 연결한다

2026-09-11. 상태: 사용자 요청에 따른 개선 설계안 반영, 구조적 ADR 초안 승인 대기. 구현·배포·운영 검증 완료를 의미하지 않는다.

이 묶음은 2026-09-10 감사 F01–F21의 후속 명세다. [원본 감사](../../report/2026-09-10-architecture-review.md)는 수정하지 않는다. 같은 항목에서 기존 planning과 충돌하면 **이 개선안이 최신 구현 검토 기준**이다. ADR 등록과 accepted 상태는 별도 승인이다. GitHub Issues 발행 전까지 이 묶음의 검증 항목은 Issue 후보이며 영구 backlog가 아니다.

1. [구조·모듈·DB 소유권](architecture.md): runtime와 compile 방향, port/adapter, DDD 불변식.
2. [인증·영속 모델·보존](data-and-identity.md): 테이블·제약·OAuth·소유권·저장/재개.
3. [FE REST 계약](api-contract.md): 지도 범위·cursor·여정 상태 전이·오류·FE 갱신.
4. [검색·선택 RAG·평가](retrieval.md): 순서가 있는 ranking pipeline과 버전 관리.
5. [실행·동기화·운영 정책](runtime-and-operations.md): 용량, deadline, LKG, 복원, release 게이트.
6. [3라운드 토론](debate.md): backend architect/developer 관점의 찬반 검토. 한 검토자가 두 페르소나를 수행했으며 독립 에이전트 검증으로 부르지 않는다.
7. [ADR 승인 자료](adr-review.md): 제목·대안·결정 제안·부작용·영향 경로·slug.
8. [개선 추적·예상 재평가](../../report/2026-09-11-design-remediation.md): 원래 배점에 따른 예측과 남은 검증.

## 범위와 미확인 사항

여정 탐색·카카오 로그인·저장/재개가 제출 MVP다. 지도 커뮤니티 조회/작성은 상세 계약을 준비하되 여정 MVP 출시의 필수 범위를 자동 확대하지 않는다. 사용자 확인: 게시글은 한옥·한옥 숙박·한옥 카페·전통시장 방문 후 남기는 짧은 후기와 좋아요다. 기존 온기의 mood/score와 분리하며 대댓글은 없다. 댓글 전체와 사진 첨부는 이번 범위에서 제외 제안한다. 지도 공개 조회는 현재 위치에 제한되지 않으며 수집된 타지역 데이터도 조회 가능하다. 데이터 지원 범위와 위치 권한은 다른 개념이다.

무료 호스팅 업체, 실제 메모리/connection 한도, 외부 모델 budget, 알림/복구 담당자와 실제 도메인은 아직 확인되지 않았다. 숫자는 초기 제한 설정과 검증 목표이며 처리량 보증이 아니다. PostgreSQL은 이 요구에 맞는 기본안이지 실측 없이 ‘가장 빠른 DB’라는 결론이 아니다.

FE 원본 `docs/specs`는 외부 저장소 symlink다. 이번에는 그 원본을 수정하지 않고 이 저장소의 계약으로 변경안을 제공한다. 원본 계약의 변경 ID는 api-contract의 추적표로 연결한다.
