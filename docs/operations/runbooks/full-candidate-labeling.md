# #718 TourAPI 선별 후보 전수 1차 라벨링

2026-10-12 실행: TourAPI `areaBasedList2`의 34개 신분류·8개 정확한 구분류, 기본 키워드 8개와 기존 검증 장소 제목 17개를 72페이지 호출했다. 중복 검색어를 제거한 67개 필터 응답의 총 14,936행을 수신했고 페이지별 `totalCount`·필터 일치·고유 `contentId`를 확인했다. 중복 3,853행을 제거한 고유 후보 **11,083건 전부**에 `discovery-candidate-v1.0.0` 판정, 원천 해시, 근거 조사 상태, 출처 기반 표시 문구를 부여했다. INCLUDE 1,982, REVIEW 5,065, EXCLUDE 4,036, provider payload 충돌 0. 이 수치는 실제 재호출 결과이며 기존 운영 원천 기준선 11,087건과는 11,078건이 겹친다.

[한국문화정보원 촬영지 공개 CSV](https://www.data.go.kr/data/15111405/fileData.do) 15,034행을 사용했다. 다운로드 SHA-256은 `1eba00010a3274e58a302d3dfdf0b7e56b4d7af3fe03680edfeae9866845ce3c`로 이전 검수 원천과 일치한다. drama/movie에 한해 정규화된 장소명이 같고 좌표 거리가 30m 이하인 관계 **217개, 장소 99곳**을 `OFFICIAL_CANDIDATE_REVIEW`로 표시했다. 이는 작품·촬영 관계의 **후보**이며 작품명·장면 요약을 공개 승인한 수가 아니다. 이전에 공개 검증된 26관계/17곳 중 새 후보와 겹치는 것은 26관계/17곳이다. `contentId=2717818`(옥천성당)은 기본 필터 밖이지만 검증 장소 제목의 동적 검색으로 구제했다. 결과의 원래 ID 일치와 게시 직전 보존 gate가 필요하다.

로컬 산출물은 Git 추적에서 제외된 `tmp/kcontents-718/`에 두었다. `tourapi-capture.json`, `previous-catalog.jsonl`, `official-filming.csv`, `verified-relations.jsonl`, `labels.jsonl`, `manifest.json`을 재현·검수에 사용한다. `manifest.json`의 labelSetSha256은 `1d72792b994a0b70654123a417025489299a98d50eeb2c19ad745deb5b42d2b5`다. 외부 API 키와 DB 비밀번호는 산출물에 넣지 않는다.

동일 공식 CSV의 드라마·영화·예능·아티스트 행 중 좌표가 유효한 15,028행을 사용해 **11,083곳 전체**에 공간·이름 근거 대조를 추가했다. 기존 검증 17곳, 100m 이내 동일 이름 248곳, 100m~1km 동일 이름 82곳, 100m 이내 이름 일부 일치 78곳, 100m 이내 다른 장소 1,344곳, 해당 자료에서 근접 근거가 없는 9,308곳, 좌표가 없어 비교 불가능한 6곳이다. `evidence-audits.jsonl`에는 장소별 공식 CSV 행 번호·출처 제목·미디어 유형·공식 장소명·좌표 거리와 조사 상태를 기록했고, `labels-reviewed.jsonl`에는 이 감사 결과를 합쳤다. `artist`의 제목은 작품명이 아닌 아티스트명일 수 있다. 감사 SHA-256 `dc037b536d82ac82ea38c133cc5657ba7648ca57120358df7630c0d56d9ce6cb`을 `evidence-audit-manifest.json`과 `manifest-reviewed.json`에 보존한다. **이는 공식 자료 한 종류에 대한 전수 대조**이며, 9,308곳을 K-콘텐츠와 무관하다고 판정하거나 새 후보 1,752곳을 검증 완료로 승격한 결과가 아니다. 가까운 다른 장소 1,344곳은 오인 위험이 높아 별도 검수 상태를 유지한다. 추가 조사는 이 순서대로 하며 LLM 호출은 근거가 확보된 건에만 제한한다.

공식 후보 217관계의 장면 설명에만 Gemini `gemini-3.5-flash-lite`를 호출했다. `scene-summary-drafts.jsonl`에 **출처 원문 인용과 함께 210개 요약 후보**, 장면 정보가 부족한 7개 빈 요약을 남겼다. 모든 인용 문구가 해당 CSV 행의 장소설명에 실제로 포함되는지 기계적으로 검사했다. 요약은 전부 `REVIEW_REQUIRED`이며 공개 관계나 검증 완료 요약으로 승격하지 않았다. 모델 출력 파일의 SHA-256은 `5510df5ea08dd00e412f618b8a27a2597419b9074f6732302db8032b6b4494b2`다.

재실행 절차: `ONMARU_TOURAPI_SERVICE_KEY` 환경변수를 설정하고, 운영 DB의 기존 검증 장소 제목 목록을 먼저 읽기 전용으로 확보한다. `python3 scripts/policy-validation/capture-tourapi.py --required-keyword '검증 장소 제목 1' --required-keyword '검증 장소 제목 2' --output tmp/kcontents-718/tourapi-capture.json`처럼 **모든 검증 장소 제목**을 반복 지정한다. 기본 필터만 실행하면 옥천성당을 누락한다. 이전 운영 catalog 스냅샷은 `scripts/policy-validation/readonly-catalog-snapshot.sql`로 읽기 전용 추출한다. 촬영지 CSV와 기존 검증 관계를 확보한 뒤 `python3 scripts/policy-validation/label-selected-candidates.py <capture> <previous-snapshot> --official-csv <official-csv> --verified-relations <verified-jsonl> --labels <labels-jsonl> --manifest <manifest-json>`을 실행한다. 이어 `python3 scripts/policy-validation/audit-official-filming-coverage.py <capture> <labels-jsonl> <official-csv> --label-manifest <manifest-json> --audits <audits-jsonl> --manifest <audit-manifest-json> --enriched-labels <reviewed-labels-jsonl> --enriched-manifest <reviewed-manifest-json>`을 실행한다. 산출물의 고유 ID 수, 중복, 충돌, 입력 SHA, 새/기존 차집합을 검사한다.

**현재 경계:** 라벨 파일과 전수 근거 대조는 로컬 가공 결과다. 새 revision/운영 DB에는 아직 적재되지 않았고 FE API가 읽는 값도 아니다. `INCLUDE`도 상세·권리 gate 전의 정책 판정이다. `displaySummary`는 TourAPI 제목과 주소를 연결한 출처 기반 문구이며 LLM 장면/작품 요약이 아니다. 공식 CSV 후보의 장면 설명과 장소 동일성 검수, 기존 검증 장소 보호, 운영 적재 및 API 회귀를 완료한 뒤 공개한다.

V050 migration이 적용되면 `k_content_label_batches`/`k_content_candidate_labels`에 전수 라벨과 공식 자료 대조 결과를 **STAGED**로 보관할 수 있다. `scripts/operations/prepare-kcontent-label-import.py`는 기본 `ROLLBACK` SQL을 생성하며, `--apply`를 명시한 경우에만 transaction을 commit한다. 결정 집합과 근거 감사 SHA-256을 모두 확인한다. `--publish`는 기존 검증 장소가 후보 밖에 있으면 거부한다. 이번 스냅샷은 검증 장소 17곳을 모두 포함한다. 다만 DB 적재와 FE 검증 전에는 배치를 active로 바꾸지 않는다. 가져온 행의 총수와 결정 집합 SHA-256을 DB에서 다시 검사하며 동일 batch 재실행은 멱등이다. 이 내부 배치와 `selected_discovery_active`는 별도 pointer다.

```bash
python3 scripts/operations/prepare-kcontent-label-import.py \
  tmp/kcontents-718/labels-reviewed.jsonl tmp/kcontents-718/manifest-reviewed.json \
  --output tmp/kcontents-718/import-reviewed-preview.sql
```

## FE #366 전달용 로컬 요약과 이미지 경계

이번 세션에서는 외부 LLM 호출 없이 11,083곳 모두에 `placeSummaryPoints` 3개를 생성했다. 장소명·정책 역할, TourAPI 주소, 공식 촬영지 자료 대조 상태만 기술하며 미검증 촬영 사실을 주장하지 않는다. `manifest-reviewed.json`의 `placeSummaryPolicyVersion`과 `placeSummarySetSha256`으로 요약 집합을 고정한다. 이는 작품 상세의 `summaryPoints`와 별개의 장소 설명이다.

`tmp/kcontents-718/fe-place-labels.jsonl`은 FE #366 계약과 대조할 내부 전달본이다. 11,083건 모두 `placeId`가 있고 8,678건은 기존 공개 ID, 2,405건은 현재 read store와 같은 `p-tourapi-{contentId}` fallback을 쓴다. 근접 후보의 공식 행 번호는 검수용이며 `reviewRequired=true`다. 미검증 작품명은 FE 전달본에서 제외했다. 이 파일은 현재 FE API가 제공하지 않고 `INTERNAL_REVIEW_ONLY` 상태다. 검증된 작품 관계는 기존 17곳에 한한다.

한국문화정보원 공개 촬영지 CSV에는 이미지 URL 열이 없다. 따라서 `workArtwork=null`을 유지한다. 장소 이미지는 TourAPI 권리 정보와 개별 이미지 사용 조건을 확인한 기존 `placeImage`/`images[]` 경로를 따른다. 미확인 포스터·스틸을 FE에 전달하지 않는다.

```bash
python3 scripts/policy-validation/export-kcontents-fe-handoff.py \
  tmp/kcontents-718/labels-reviewed.jsonl tmp/kcontents-718/previous-catalog.jsonl \
  --output tmp/kcontents-718/fe-place-labels.jsonl \
  --manifest tmp/kcontents-718/fe-place-labels-manifest.json
```

## 운영 적재 전 공식 작품명 조사

사용자 요청에 따라 기존 장소 라벨의 운영 INSERT보다 작품 조사 단계를 앞에 둔다. `label-official-works.py`는 동일한 [한국문화정보원 촬영지 CSV](https://www.data.go.kr/data/15111405/fileData.do)의 드라마·영화·예능 제목을 작품명 후보로 연결한다. `artist` 유형의 제목은 아티스트명이므로 작품으로 사용하지 않는다. 원천 설명에서 뮤직비디오 제목을 직접 확인한 17행만 별도 수기 매핑했다. 장소명 정규화 일치 또는 수기 검수한 32개 장소 별칭, 같은 도로명·건물번호, 250m 이내 좌표, 비어 있지 않은 장면 설명을 모두 충족한 관계를 `SOURCE_PLACE_MATCH`로 둔다. 설명이 촬영 사실을 지지하지 않는 5개 행은 수기 검토 대기로 되돌렸다. 이것도 내부 자료 대조 상태이며 FE 공개 승인 상태 `VERIFIED`와 다르다.

2026-10-12 11,083곳 전수 조사에 아티스트 방문·촬영 콘텐츠(`ARTIST`)를 수용한 결과, 콘텐츠가 발견된 장소는 378곳, `SOURCE_PLACE_MATCH` 610관계, 주소·거리·설명 추가 확인이 필요한 `REVIEW_REQUIRED` 247관계(총 857관계)다. `official-work-labels.jsonl`은 11,083행으로 원천 행 번호·작품/아티스트명·유형·설명·장소 동일성 검사 결과를 보존한다. `labels-with-works.jsonl`과 `manifest-with-works.json`은 이전 장소 라벨과 이 조사 결과를 동일 `contentId` 순서로 결합한다. 기존 `import-reviewed-apply.sql`은 작품 조사를 포함하지 않은 이전 산출물이므로 **사용하지 않는다**. 새 배치 SQL은 `labels-with-works.jsonl`에서 다시 생성한다.

```bash
python3 scripts/policy-validation/label-official-works.py \
  tmp/kcontents-718/labels-reviewed.jsonl tmp/kcontents-718/official-filming.csv \
  --output tmp/kcontents-718/official-work-labels.jsonl \
  --manifest tmp/kcontents-718/official-work-manifest.json \
  --label-manifest tmp/kcontents-718/manifest-reviewed.json \
  --enriched-labels tmp/kcontents-718/labels-with-works.jsonl \
  --enriched-manifest tmp/kcontents-718/manifest-with-works.json
python3 scripts/operations/prepare-kcontent-label-import.py \
  tmp/kcontents-718/labels-with-works.jsonl tmp/kcontents-718/manifest-with-works.json \
  --output tmp/kcontents-718/import-with-works-preview.sql
```

작품명을 찾지 못한 장소에는 작품 관계를 만들지 않는다. 특히 가까운 다른 장소, 도로명 주소가 다른 동명 장소, 아티스트 방문·화보는 촬영 작품으로 간주하지 않는다. 원천 CSV는 2022년 자료이므로 이후 작품을 모두 포함하지 않는다. 작품 공개 전에는 원천 유형·제목 중복/동명이작·장면 설명을 추가 검수하고 기존 `k_content_public_relations` 검증 gate로만 FE에 제공한다.
