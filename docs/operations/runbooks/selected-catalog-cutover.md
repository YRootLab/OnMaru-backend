# #716 운영 TourAPI 선별 카탈로그 전환

## 수집 범위

새 `kto-korean-tour` 원천 선택 모드는 신분류 `lclsSystm3` 34개, 좁은 구분류 `contentTypeId`+`cat1/2/3` 8개, 키워드 8개를 호출해 `contentId`로 합친다. 구분류는 한옥, 고택·종택, 궁궐·누각, 전통마을, 한옥카페, 한옥숙소, 전통체험, 전통시장이다. 키워드는 한옥·고택·종택·고궁·궁궐·전통마을·한옥스테이·전통시장이다. 구분류/키워드 결과도 원천 유효성, 신분류 의미 판정, 좌표, 상세·권리 검토를 거친다. 검색어가 맞는다는 이유만으로 K-Contents 촬영 관계나 장소 공개를 확정하지 않는다.

2026-10-12 운영 기존 원천 revision을 읽기 전용으로 대조한 기준선: 신분류 9,801행, 구분류 4,685행, 교집합 3,461행, 합집합 11,025행. 키워드에서만 추가되는 `contentId`는 이 기준선에 포함되지 않는다. 이는 **기존 저장 원천의 분포**이며 재호출 결과나 최종 공개 건수가 아니다.

## 실행 안전 장치

게시 transaction은 새 revision에 기존 공개 검증 K-Contents 관계의 장소가 한 곳이라도 빠지면 실패한다. 2026-10-12 실제 재호출에서는 옥천성당(`contentId=2717818`)이 기본 선별 조건 밖이었다. 현재 활성 공개 관계의 TourAPI 원천 제목을 추가 키워드로 검색하고 **원래 contentId가 결과에 있는지 확인**해 구제한다. `옥천성당` 검색은 실제 TourAPI에서 정확히 1건이며 해당 ID·좌표·구분류를 확인했다. 검색 실패·ID 누락·새 revision의 활성 장소 누락 시 pointer 교체를 중단한다. 읽기 전용 preview도 같은 구제 입력을 사용한다.

`ONMARU_TOURAPI_SYNC_SELECTED_SOURCE=false`가 기본이다. 이 상태로 새 버전을 배포해도 기존 전체 수집 경로가 유지된다. `true`인 배포에서 기존 카탈로그 sync를 실행하면 선택 목록을 완성·중복 제거한 뒤에만 새 revision을 적재한다. 모든 페이지의 `totalCount`와 행 수, 정확한 필터 응답, 공식 신분류 코드, 충돌을 확인한다. 빈 결과와 미완성 배치에서는 활성 포인터를 바꾸지 않는다.

운영 전환 전에 같은 코드로 후보 수·신규/중복/격리·예상 활성 장소 수·카테고리/지역 분포를 확인한다. 이어 기존 활성 23,598곳과의 차집합 및 FE의 홈·지도·한옥·상세·찜·K-Contents 관계를 확인한다. 공개 장소가 빠지면 그 장소의 기존 찜·후기·스탬프·오디오·K-Contents 관계는 identity 참조가 유지돼도 활성 장소 조회와 상세 API에서 보이지 않을 수 있다. 따라서 새 revision을 게시한 후 아래 차집합을 확인하고, FE 회귀가 끝나기 전에는 전환을 완료로 기록하지 않는다. 새 게시에서는 이전 revision을 즉시 삭제하지 않으며 retention은 KTO 최신 2개 게시본과 최근 14일 게시본을 보존한다. 검증 실패 시 플래그를 내리고 이전 `catalog_active_datasets` revision으로 복귀한다. 자동 크론이 먼저 전환하지 않도록 배포 시점과 마지막 성공 sync를 확인한다.

```sql
-- 게시 직전 활성 revision UUID를 별도 기록한다. 새 revision 게시 후 :previous_revision과 비교한다.
WITH removed AS (
  SELECT old.place_id
  FROM onmaru.catalog_place_versions old
  WHERE old.revision_id = :previous_revision AND old.status = 'ACTIVE'
  EXCEPT
  SELECT current.place_id
  FROM onmaru.catalog_place_versions current
  JOIN onmaru.catalog_active_datasets active ON active.revision_id = current.revision_id
  WHERE active.dataset = 'kto-korean-tour' AND current.status = 'ACTIVE'
)
SELECT (SELECT count(*) FROM removed) AS removed_places,
       (SELECT count(*) FROM onmaru.journey_saved_places saved
        JOIN onmaru.catalog_place_public_ids ids ON ids.public_id = saved.place_id
        JOIN removed ON removed.place_id = ids.place_id) AS affected_saves,
       (SELECT count(*) FROM onmaru.community_visit_reviews review
        JOIN onmaru.catalog_place_public_ids ids ON ids.public_id = review.place_id
        JOIN removed ON removed.place_id = ids.place_id) AS affected_reviews,
       (SELECT count(*) FROM onmaru.k_content_place_relations relation
        JOIN removed ON removed.place_id = relation.place_id) AS affected_kcontents;
```

영향받는 관계가 있으면 FE에서 기존 찜·후기와 K-Contents 화면 동작을 확인하고 필요한 호환 조회/데이터 이관을 별도 적용한다. 자동으로 관계를 삭제하거나 새로운 장소에 재연결하지 않는다. 지도는 새 활성 revision의 `map_place_read_projection` 건수와 `catalog_place_versions`의 `ACTIVE` 건수가 같은지 확인하고, viewport·목록·상세 응답을 실제 좌표와 public ID로 점검한다.

```sql
-- 오류가 확인됐을 때, 기록한 UUID가 실제 PUBLISHED 상태인지 확인한 뒤 한 트랜잭션에서 복귀한다.
BEGIN;
UPDATE onmaru.catalog_active_datasets active
SET revision_id = :previous_revision, activated_at = now()
WHERE active.dataset = 'kto-korean-tour'
  AND EXISTS (SELECT 1 FROM onmaru.catalog_dataset_revisions revision
              WHERE revision.id = :previous_revision AND revision.status = 'PUBLISHED');
-- 정확히 1행 변경됐는지 확인하고 COMMIT한다. 아니면 ROLLBACK한다.
COMMIT;
```

새 이미지가 운영에 배포된 뒤 호스트의 `infra/lightsail`에서 다음 **읽기 전용** one-shot을 실행한다. 이 명령은 카탈로그 게시 세션을 시작하지 않으며 원천 호출 결과의 `raw`, `qualified`, `skipped`, `sourceQuarantined`, `qualificationQuarantined`와 카테고리별 수만 출력한다. 필터 불일치와 `contentId` 충돌이 발견되면 결과를 실패 처리한다. 기존 API 컨테이너는 그대로 둔다.

```bash
sudo docker compose --env-file .env -f compose.yaml run --rm --no-deps \
  -e ONMARU_TOURAPI_SELECTED_PREVIEW=true \
  -e ONMARU_TOURAPI_SYNC_ON_STARTUP=false \
  spring-green --spring.main.web-application-type=none
```

최종 전환은 사전 점검과 FE 회귀 결과를 기록한 뒤 `ONMARU_TOURAPI_SYNC_SELECTED_SOURCE=true`인 실행 환경에서 기존 카탈로그 sync를 한 번 수행한다. 자동 크론이 우연히 먼저 실행되지 않도록 시각을 점검한다. 게시 전후 `catalog_active_datasets` revision과 활성 장소 수를 기록하고, 이전 revision ID를 복귀 대상으로 보관한다.

`selected_discovery_*`는 신규 FE 탐색 API의 별도 승인 게시본이다. 기존 카탈로그 전환과 자동으로 같은 포인터를 공유하지 않으므로 FE의 두 경로를 각각 검증한다. 기존 수집은 `ONMARU_TOURAPI_SYNC_SELECTED_SOURCE`로, 주간 선별 탐색 scheduler는 `ONMARU_DISCOVERY_ENABLED`로 제어한다.
