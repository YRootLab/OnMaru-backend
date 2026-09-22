# 홈·오디오 API FE 연동 보고서 (2026-09-21, 2차 갱신)

FE에 전달하는 최신 API 연동 상태 보고서. Base URL: `https://onmaru-backend.onrender.com` (v0.3.12 이상 배포 기준). 모든 응답은 `Cache-Control: no-store`, 목록 계약은 `schemaVersion: 1.2`다.

---

## 0. 이번 신규 작업 요약 (이번 PR에 포함 — 배포 대기)

이번 세션에서 **신규 API 3종 + 찜 데이터 영속화**를 추가했다. 상세 계약은 각 문서 참고.

| 신규 | 용도 | 계약 문서 |
|---|---|---|
| `GET /api/v1/odii/regions` | "지도로 듣는 이야기" 지역 탭 (서울·경기·인천 5, 강원 4 …) | `docs/toFE/odii-region-groups.md` |
| `GET /api/v1/home/popular-sounds` | 홈 "인기 한옥 소리" **이번 주 TOP N** 랭킹 (기본 7) | `docs/toFE/popular-sounds.md` |
| `POST /api/v1/odii/stories/{storyId}/plays` | 오디오 재생 시작 기록 (인기 랭킹의 재생 수 신호) | `docs/toFE/popular-sounds.md` §2 |

**찜(저장) 영속화**: 지금까지 찜은 앱 메모리에만 있어서 재배포 때마다 사라졌다. 이번부터 Neon DB(`journey_saved_places`, `journey_saved_odii_stories`)에 영속 저장되어 재배포 후에도 유지된다. 인기 랭킹의 저장 수 신호도 이 DB 집계를 사용한다.

**인기 점수 정의**: `2 × 최근 7일 재생 수 + 1 × 최근 7일 저장 수`. 재생/저장 신호가 아직 없으면 최근 게시 순으로 폴백(`basis: FALLBACK_RECENT`)하므로 FE는 항상 7개 카드를 렌더링할 수 있다.

**재생 기록 호출 방식**: FE는 오디오 재생 버튼을 누를 때 `POST /api/v1/odii/stories/{storyId}/plays`를 1회 호출한다. 기존 POST와 동일하게 CSRF 토큰(`X-CSRF-TOKEN` + `__Host-onmaru-csrf` 쿠키)을 동봉하고, 실패해도 UI에 영향 없이 조용히 무시하면 된다.

---

## 1. 이번 주 추천 한옥 코스 — ✅ 사용 가능 (이미지 문제 해결됨)

### `GET /api/v1/home/curated-courses`

- **페이징**: cursor 방식 지원. `limit`(1~50, 기본 20), `cursor`, `hasMore`, `nextCursor` 계약. FE는 카드 수를 고정하지 말고 `items`/`hasMore` 기준으로 렌더링하고, `nextCursor`를 다음 요청의 `?cursor=`로 전달하면 된다.
- **이미지**: 운영 검증 완료(2026-09-21). `limit=25` 기준 25개 중 24개가 실제 TourAPI 이미지 URL(`tong.visitkorea.or.kr`)이고 직접 다운로드 테스트(200, image/jpg) 통과. `thumbnailUrl: null` 1건은 FE placeholder 사용.
- 파라미터: `keyword`, `regionCode`, `category`(한옥+관광 카테고리 11종), `hasImage=true`(이미지 있는 카드만), `limit`, `cursor`.
- 이전에 받아지던 `cdn.onmaru.example` 가짜 URL은 더 이상 반환되지 않는다. 캐시 문제로 보이면 새로고침.
- 상세 계약: `docs/toFE/home-api-spec.md`

## 2. 오디오 지역 그룹 조회 — 🆕 신규 (#306, 이번 브랜치에서 추가)

"지도로 듣는 이야기" 화면의 지역 탭(서울·경기·인천 5, 강원 4 …)을 채우는 신규 API다.

### `GET /api/v1/odii/regions` (호환 경로: `GET /api/v1/audio/regions`)

```json
{
  "schemaVersion": "1.2",
  "language": "ko-KR",
  "languageStatus": "EXACT",
  "groups": [
    { "label": "서울·경기·인천", "regionCodes": ["kr-11", "kr-23", "kr-41"], "storyCount": 5 },
    { "label": "전북", "regionCodes": ["kr-45"], "storyCount": 4 }
  ]
}
```

- `groups`: 스토리 1건 이상인 그룹만, BE 고정 순서(서울·경기·인천 → 강원 → 충북 → 충남·대전·세종 → 경북·대구 → 전북 → 전남·광주 → 경남·부산·울산 → 제주).
- `regionCodes`: 법정동 시도코드 (`kr-11` 서울, `kr-41` 경기, `kr-45` 전북, `kr-50` 제주 등).
- 그룹의 스토리 목록은 기존 `GET /api/v1/odii/stories?regionCode=...` 사용.
- `503`: Odii 데이터 미적재(아래 3번 참고) → 재시도 UI. `400`: language 형식 오류.
- 상세 계약: `docs/toFE/odii-region-groups.md`

## 3. 인기 한옥 소리 — 🔴 운영 503, 복구 진행 중 (#307)

### `GET /api/v1/home/trending-sounds` 및 `GET /api/v1/odii/stories`

- 엔드포인트·코드는 배포 완료. 현재 운영에서 **503 SERVICE_UNAVAILABLE** — 원인은 Odii 오디오 데이터가 DB에 적재되지 않은 것(동기화가 원천 fetch 단계에서 실패).
- **최유력 원인**: Render 환경변수 `ONMARU_SECRETS_SOURCE` 기본값이 `fake`라서, `environment`로 설정돼 있지 않으면 가짜 서비스 키로 Odii API를 호출하게 됨. Render env에서 아래 두 가지 확인 필요:
  1. `ONMARU_SECRETS_SOURCE=environment`
  2. `ONMARU_SECRET_ODII_SERVICE_KEY_CURRENT` = 공공데이터포털 Odii(소리마루) 서비스키
- 복구되면 503 → 200으로 자동 전환되며 FE 코드 변경은 불필요. 그 전까지 FE는 503 시 빈 목록 + 재시도/준비 중 UI 표시 (가짜 카드로 채우지 않는 계약 유지).
- **"인기" 측정 방식 (참고)**: 현재 정렬은 활성 스토리의 최근 게시 순(publishedAt)이고 조회수/재생수 집계는 아직 없음. 진짜 인기 랭킹(저장 수 기반 등)은 후속 이슈로 설계 필요.

## 4. 인기 지역 — ✅ 사용 가능

### `GET /api/v1/home/popular-regions`

- 공개 방문후기 수 기준 지역 집계. 정상 응답(200) 확인.
- `regionCode`가 FE의 지역 코드 체계와 동일하므로 하드코딩 불필요.
- `parentRegionCode`로 특정 시/도 하위만 조회 가능.

## 5. 검색창 — ✅ 사용 가능 (CSRF 필요)

### `POST /api/journey-curator/explore`

- canonical `POST /api/v1/explorations`의 호환 경로. `Content-Type: application/json`, `Idempotency-Key`, `X-CSRF-TOKEN` 필요 (`credentials: "include"` 유지).
- CSRF 토큰은 쿠키 발급 엔드포인트에서 선발급.

---

## 연동 체크리스트

| 화면 섹션 | API | 상태 |
|---|---|---|
| 홈 첫 섹션 (이번 주 추천 한옥 코스) | `GET /api/v1/home/curated-courses?limit=20` | ✅ 페이징·이미지 정상 |
| 지도로 듣는 이야기 — 지역 탭 | `GET /api/v1/odii/regions` | 🆕 신규 (배포 후 사용) |
| 지도로 듣는 이야기 — 스토리 목록/재생 | `GET /api/v1/odii/stories` (+`/{storyId}`) | 🔴 503 → 복구 대기 (#307) |
| 인기 한옥 소리 섹션 (이번 주 TOP N) | `GET /api/v1/home/popular-sounds` | 🆕 신규 (배포 후 사용, 재생 기록 호출 병행) |
| 오디오 재생 기록 | `POST /api/v1/odii/stories/{storyId}/plays` | 🆕 신규 (배포 후 사용) |
| 지역별 둘러보기 | `GET /api/v1/home/popular-regions` | ✅ |
| 검색창 | `POST /api/journey-curator/explore` | ✅ |

신규 지역 그룹 API는 브랜치 `feature/306-region-audio-groups`(커밋 `2d1f649`)에서 구현 완료, 운영 배포는 다음 릴리즈에 포함.
