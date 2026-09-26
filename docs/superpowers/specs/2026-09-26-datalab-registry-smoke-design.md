# DataLab 지역 registry와 staging smoke 설계

## 목표와 범위

Issue #399의 검증 가능한 지역 mapping registry와 fail-closed 수집 정책을 기존 DataLab 방문자 파이프라인에 추가하고, Issue #392의 운영 검증을 GitHub `staging` environment에서 반복 실행할 수 있는 smoke workflow로 만든다. CI Toolkit rollout과 다른 provider 수집기는 변경하지 않는다.

완료 상태는 저장소 구현과 자동화가 검증된 상태를 뜻한다. 실제 staging secret과 배포가 아직 없으면 workflow는 성공으로 가장하지 않고 누락 설정을 preflight 실패로 기록한다. credential, 원본 provider 응답, 개인 식별자는 commit하거나 artifact에 남기지 않는다.

## 선택한 접근

기존 `catalog_region_source_codes`를 mapping identity와 유효기간의 단일 원천으로 유지하고, DataLab 전용 registry 테이블을 1:1 확장으로 둔다. 범용 테이블에 DataLab 전용 상태와 provenance를 섞는 방식은 다른 provider의 의미를 오염시키고, 별도 독립 registry는 source-code mapping을 중복 저장한다. 확장 테이블은 기존 FK를 참조해 두 문제를 모두 피한다.

애플리케이션은 registry 전체를 읽되 `ACTIVE`이면서 구조와 provenance가 유효한 mapping만 수집 대상으로 선택한다. `PENDING`과 `REJECTED`는 구조화된 제외 결과로 남고, 활성 mapping이 하나도 없으면 provider를 호출하거나 revision을 게시하지 않는다.

## 데이터 모델과 무결성

신규 migration은 `PENDING`, `ACTIVE`, `REJECTED` 상태 enum과 DataLab registry 확장 테이블을 만든다. 각 행은 기존 source-code mapping을 참조하고 다음 정보를 가진다.

- Catalog region identity에서 파생되는 `internalRegionCode`
- 기존 source mapping의 `source_code`인 `dataLabRegionCode`
- `level`, provider가 반환한 `name`
- `sourceUrl`, `sourceObservedAt`, `verifiedBy`, `verifiedAt`
- `status`

동일 내부 region은 registry에 한 번만 존재하고 DataLab source code도 한 번만 존재한다. DB constraint와 trigger는 source code의 `SIDO:`/`SIGUNGU:` prefix, Catalog region level, SIDO의 parent 부재, SIGUNGU의 SIDO parent 존재를 검증한다. `ACTIVE`는 HTTPS source URL과 모든 관측·검증 필드가 반드시 있어야 한다. `PENDING`과 `REJECTED`는 provenance가 아직 없을 수 있지만 빈 문자열과 잘못된 URL은 허용하지 않는다.

기존 네 공식 mapping은 migration에서 `ACTIVE`로 backfill한다. seed 함수는 generic mapping, verification 이력, registry row를 한 transaction 안에서 만들며 다른 region에 이미 연결된 source code는 덮어쓰지 않는다.

## 수집 흐름과 실패 처리

JDBC registry lookup은 상태를 포함한 typed mapping을 반환한다. adapter는 먼저 registry를 평가하고 다음 구조화된 결과를 만든다.

- `COLLECTED`: 검증된 mapping의 COMPLETE 또는 NOT_AVAILABLE 관측
- `SKIPPED`: `PENDING`, `REJECTED`, 활성 mapping 없음
- `QUARANTINED`: 잘못된 계층·source code, 요청 scope와 응답 scope 불일치, 중복 응답, 필수 활성 region 누락

활성 mapping이 없으면 외부 client 호출 수는 0이고 revision writer도 호출되지 않는다. 활성 mapping이 있으면 기존 전국 SIDO/SIGUNGU 요청을 각각 한 번 수행하되 registry에 없는 응답은 저장하지 않는다. 이 요청은 특정 제외 region을 대상으로 한 호출이 아니므로 PENDING/REJECTED mapping이 호출 인자로 전달되는 일도 없다.

활성 mapping의 응답 scope/code가 맞을 때만 mapping의 `internalRegionCode`로 observation을 만든다. provider의 이름이나 코드를 내부 코드로 추론하지 않는다. 활성 mapping 누락, 중복, scope 불일치가 하나라도 있으면 전체 batch를 quarantine하고 이전 active revision을 유지한다. nullable count는 `null + NOT_AVAILABLE`로 유지한다.

ingestion service는 구조화된 sync 결과를 반환한다. publish 가능한 batch만 revision writer에 넘기고, skip/quarantine 결과는 observer에 전달한다. Spring adapter는 Micrometer counter를 `outcome`과 사전에 정의된 low-cardinality `reason`만으로 기록한다. region code, provider payload, URL은 metric label에 넣지 않는다.

## staging smoke 자동화

수동 실행 전용 GitHub Actions workflow는 `staging` environment에서 다음 순서로 동작한다.

1. 필요한 URL과 secret의 존재만 검사하고 값을 출력하지 않는다.
2. staging Spring API의 보호된 운영 sync endpoint를 service token으로 한 번 호출한다.
3. read-only SQL로 registry 상태별 개수, 네 공식 ACTIVE mapping의 provenance, active DataLab revision, 최신 관측과 coverage를 검증한다.
4. 공개 Insights와 VisitReview API에서 visitor 값이 활성 revision 값 또는 `null`인지 확인한다.
5. 의도적으로 실패하는 provider URL로 별도 sync를 실행하는 대신, 통합 테스트와 DB revision pointer 불변 검증을 workflow evidence에 연결한다. staging의 정상 provider 설정은 변경하지 않는다.
6. secret을 제외한 revision ID, 개수, timestamp, HTTP status만 JSON artifact로 보존한다.

운영 sync endpoint는 기존 operator/service-token 경계와 같은 constant-time 비교 인증을 사용하고 production profile에서만 생성한다. 요청 body나 임의 provider URL을 받지 않으며 동시 실행은 lock으로 한 번만 허용한다. 응답은 sync outcome, reason별 개수, 게시 revision 식별자만 포함한다.

현재 staging environment에 필요한 설정이 없으므로 workflow 최초 실행은 preflight에서 실패할 수 있다. 이 경우 #392는 열린 상태로 유지하고 누락된 secret 이름과 실행 URL만 Issue comment에 기록한다. 설정 후 재실행이 모든 acceptance criteria를 충족할 때만 #392를 닫는다.

## 테스트와 검증

- migration 통합 테스트: 중복 internal/source code, level/parent, URL/provenance, ACTIVE 필수값, seed/backfill
- adapter 단위 테스트: PENDING/REJECTED 무호출, nullable 보존, scope/code mismatch와 누락 quarantine, 이름/default fallback 부재
- ingestion 단위 테스트: skip/quarantine에서 publish 금지와 이전 revision 유지
- Spring 통합 테스트: 운영 endpoint 인증·동시 실행·sanitized response, Micrometer low-cardinality tag
- workflow script 테스트: 설정 누락, 성공 DB fixture, active revision 불변, artifact redaction
- 전체 Gradle, Node, contract, migration/restore 검증과 `git diff --check`

## 완료 및 이슈 처리

#399는 구현·테스트·문서가 merge 가능한 상태이고 PR이 `develop`에 병합된 뒤 acceptance criteria를 대조하여 닫는다. #392는 자동화 구현만으로 닫지 않는다. 실제 staging run에서 secret, mapping, collection, active revision, API projection, rollback 보존 증적이 모두 확인된 뒤에만 닫는다.
