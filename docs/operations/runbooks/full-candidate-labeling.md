# #718 TourAPI 선별 후보 전수 1차 라벨링

2026-10-12 실행: TourAPI `areaBasedList2`의 34개 신분류·8개 정확한 구분류와 `searchKeyword2`의 8개 키워드를 55페이지 호출했다. 50개 필터 응답의 총 14,917행을 수신했고 페이지별 `totalCount`·필터 일치·고유 `contentId`를 확인했다. 중복 3,836행을 제거한 고유 후보 **11,081건 전부**에 `discovery-candidate-v1.0.0` 판정, 원천 해시, 근거 조사 상태, 출처 기반 표시 문구를 부여했다. INCLUDE 1,982, REVIEW 5,064, EXCLUDE 4,035, provider payload 충돌 0. 이 수치는 실제 재호출 결과이며 기존 운영 원천 기준선 11,087건과는 11,078건이 겹친다.

[한국문화정보원 촬영지 공개 CSV](https://www.data.go.kr/data/15111405/fileData.do) 15,034행을 사용했다. 다운로드 SHA-256은 `1eba00010a3274e58a302d3dfdf0b7e56b4d7af3fe03680edfeae9866845ce3c`로 이전 검수 원천과 일치한다. drama/movie에 한해 정규화된 장소명이 같고 좌표 거리가 30m 이하인 관계 **216개, 장소 98곳**을 `OFFICIAL_CANDIDATE_REVIEW`로 표시했다. 이는 작품·촬영 관계의 **후보**이며 작품명·장면 요약을 공개 승인한 수가 아니다. 이전에 공개 검증된 26관계/17곳 중 새 후보와 겹치는 것은 25관계/16곳이다. 나머지 `contentId=2717818`(옥천성당)은 선별 호출 범위 밖이므로 카탈로그 전환 전 보호/구제해야 한다.

로컬 산출물은 Git 추적에서 제외된 `tmp/kcontents-718/`에 두었다. `tourapi-capture.json`, `previous-catalog.jsonl`, `official-filming.csv`, `verified-relations.jsonl`, `labels.jsonl`, `manifest.json`을 재현·검수에 사용한다. `manifest.json`의 labelSetSha256은 `ece97c1149957b25b383cc9be60f5906729b28119a09141cc54b8cd8787add6c`다. 외부 API 키와 DB 비밀번호는 산출물에 넣지 않는다.

재실행 절차: `ONMARU_TOURAPI_SERVICE_KEY` 환경변수를 설정하고 `python3 scripts/policy-validation/capture-tourapi.py --output tmp/kcontents-718/tourapi-capture.json`을 실행한다. 이전 운영 catalog 스냅샷은 `scripts/policy-validation/readonly-catalog-snapshot.sql`로 읽기 전용 추출한다. 촬영지 CSV와 기존 검증 관계를 확보한 뒤 `python3 scripts/policy-validation/label-selected-candidates.py <capture> <previous-snapshot> --official-csv <official-csv> --verified-relations <verified-jsonl> --labels <labels-jsonl> --manifest <manifest-json>`을 실행한다. 산출물의 고유 ID 수, 중복, 충돌, 입력 SHA, 새/기존 차집합을 검사한다.

**현재 경계:** 라벨 파일은 로컬 1차 가공 결과다. 새 revision/운영 DB에는 아직 적재되지 않았고 FE API가 읽는 값도 아니다. `INCLUDE`도 상세·권리 gate 전의 정책 판정이다. `displaySummary`는 TourAPI 제목과 주소를 연결한 출처 기반 문구이며 LLM 장면/작품 요약이 아니다. 공식 CSV 후보의 장면 설명과 장소 동일성 검수, 기존 검증 장소 보호, 운영 적재 및 API 회귀를 완료한 뒤 공개한다.

V050 migration이 적용되면 `k_content_label_batches`/`k_content_candidate_labels`에 전수 라벨을 **STAGED**로 보관할 수 있다. `scripts/operations/prepare-kcontent-label-import.py`는 기본 `ROLLBACK` SQL을 생성하며, `--apply`를 명시한 경우에만 transaction을 commit한다. `--publish`는 기존 검증 장소가 후보 밖에 있으면 거부한다. 현재 옥천성당이 후보 밖이므로 배치를 active로 바꾸지 않는다. 가져온 행의 총수와 결정 집합 SHA-256을 DB에서 다시 검사하며 동일 batch 재실행은 멱등이다. 이 내부 배치와 `selected_discovery_active`는 별도 pointer다.

```bash
python3 scripts/operations/prepare-kcontent-label-import.py \
  tmp/kcontents-718/labels.jsonl tmp/kcontents-718/manifest.json \
  --output tmp/kcontents-718/import-preview.sql
```
