# 선별 탐색 revision 운영 절차 (#685)

새 경로는 `onmaru.discovery.enabled=false`가 기본이다. 기존 `kto-korean-tour` 일일 수집과 `catalog_active_datasets`는 그대로 둔다. 주간 자동 실행을 켤 때만 `ONMARU_DISCOVERY_ENABLED=true`를 설정한다. 월요일 03:00 KST 회차만 등록하며 재기동 시 최신 미처리 회차 하나를 보충한다. `run_id`는 due 시각에서 결정적으로 생성되고 DB의 `due_at` UNIQUE가 중복 회차를 막는다. 상세 호출은 회차당 최대 3,000번이고 초과 시 `DETAIL_BUDGET_EXHAUSTED`로 실패하며 포인터를 유지한다.

## 스테이징 파일럿 및 사람 승인

`production,staging` 프로필을 쓰는 staging compose의 `spring-api` 이미지에서 one-shot 명령을 실행한다. 각 명령은 `--spring.main.web-application-type=none`과 `--onmaru.discovery.operator.action=...`을 사용한다. 이 action이 있으면 주간 scheduler bean은 생성되지 않는다. 서버 container 안에서 두 JVM을 동시에 실행하지 말고 `docker compose run --rm --no-deps spring-api`로 별도 one-shot container를 만든다. 이미지·DB·TourAPI secret은 해당 compose 환경의 고정 값을 사용하며 명령 출력에 키나 요청 URL을 넣지 않는다.

1. `action=run`과 고유한 **수동 회차** `due-at` ISO-8601 UTC 시각으로 후보 dry-run을 만든다. 예: `--onmaru.discovery.operator.action=run --onmaru.discovery.operator.due-at=2026-10-12T18:01:00Z`. 주간 scheduler가 사용하는 월요일 03:00 KST 정각은 피하고, 파일럿 회차마다 서로 다른 시각을 준다. 동일 due 재실행은 같은 결정론적 run ID를 사용하므로 중복 revision을 만들지 않는다. 승인 0건이면 `STAGED`이고 새 active pointer가 없다.
2. 후보 ID별 `action=preview --onmaru.discovery.operator.content-id=<ID>`를 실행한다. 출력된 title·overview와 출처를 사람이 확인한다. `listHash`, `detailHash`, `fingerprintDigest`를 기록한다. fingerprint는 **상세 merge 이후** 계산된다.
3. 실제 장소성·상세·이미지 사용 권리와 근거를 확인한 항목만 `action=approve`로 등록한다. `content-id`, `role`, 앞 단계의 `list-hash`, `detail-hash`, `fingerprint-digest`, `evidence-ref`, `reviewer`, `detail-reviewed=true`, `rights-reviewed=true`를 모두 전달한다. 현재 상세와 어느 값이든 달라지면 명령은 거절된다. 넓은 카페/체험마을도 사람의 역할 확정과 fingerprint 일치 없이는 INCLUDE가 아니다. 승인·철회는 audit 테이블에 남는다.
4. 승인 후 **같은 주라도** 별도의 새 수동 `due-at`으로 `action=run`을 실행한다. 예를 들어 dry-run `18:01:00Z` 다음에 100건 승인 게시 `18:02:00Z`, 1,000건 승인 게시 `18:03:00Z`를 사용한다. 이미 `STAGED`인 due를 다시 실행해 게시할 수 없으므로 매 게시 단계에 새 시각을 배정한다. `action=status`로 신규 active revision을 확인한다. 후보·diff·격리·지역/역할 건수는 `selected_discovery_revisions`와 `selected_discovery_candidates`를 검토한다. 품질 또는 누락으로 보류되면 기존 신규 active를 유지한다.
5. `action=revoke --onmaru.discovery.operator.content-id=<ID> --onmaru.discovery.operator.reviewer=<actor>`는 현재 승인 row를 삭제한다. 새 API의 첫 페이지·주제·상세는 **반드시 `onmaru.selected_discovery_public_visible` view**를 조회한다. 이 view는 active 후보의 승인·두 hash·역할·권리·근거를 다시 검사해 이미 게시된 항목도 철회 즉시 숨긴다. 이전 게시 revision에 고정된 유효 cursor만 예외로 `selected_discovery_public_items`와 후보·현재 approval을 동일 조건으로 재검증한다. 승인 철회는 과거 cursor에서도 즉시 비노출된다. 장소 revision은 cursor 안에서 고정하지만, 검증 관계와 근거는 현시점 공개 gate를 거쳐 철회가 즉시 반영된다.
6. 되돌릴 때 `action=rollback --onmaru.discovery.operator.expected-active=<UUID> --onmaru.discovery.operator.target-revision=<이전 PUBLISHED UUID>`를 실행한다. CAS가 맞고 target이 게시된 revision일 때만 신규 포인터를 바꾼다. 기존 공개 포인터는 건드리지 않는다.

`approved()`는 TourAPI 목록/상세 SHA-256, W2의 사람 판정, 역할, 상세 내용, 상세·권리 검수, 근거를 모두 요구한다. 공급자 오류·빈 snapshot·부분 페이지·상세 quota 초과·공개 행 누락/변경·승인 철회에서 새 게시본을 자동 대체하지 않는다. 목록 hash와 상세 hash는 `discovery-source-hash-v1`로 각각 계산하고 `modifiedtime`은 hash 비교를 생략하는 근거로 쓰지 않는다.

새로운 읽기 API #689의 현재 게시본은 `selected_discovery_public_visible`을 통해 장소 identity를 가져온다. 과거 cursor의 보존 기간(24시간) 동안에는 `PUBLISHED` revision의 public item에 V047 view와 동일한 승인 조건(`INCLUDE`, non-`MISSING`, list/detail hash·역할 일치, 상세·권리 검토, 비어 있지 않은 근거)을 적용한다. 원본 `selected_discovery_public_items` 단독 조회는 금지한다.
