# Data Contract Spec: 오디 오디오 투어 및 대본 (`DATA-ODII-AUDIO`)

> **Contract ID**: `DATA-ODII-AUDIO`  
> **Canonical Source**: `src/features/odii-audio/types/odii.types.ts`  
> **Used By**: `/odii` Page, `<OdiiAudioFeature>`, `<ScriptSyncViewer>`, `<StoryCarousel>`

---

## 1. Schema: 오디 이야기 항목 (`OdiiStoryItem`)

| Field Name | Type | Required | Nullable | Description |
| :--- | :--- | :---: | :---: | :--- |
| `tid` | `string` | Yes | No | 테마 여행 식별자 |
| `tlid` | `string` | Yes | No | 테마 여행 언어 식별자 |
| `stid` | `string` | Yes | No | 이야기(Story) 고유 식별자 |
| `stlid` | `string` | Yes | No | 이야기 언어 식별자 (한국어=1) |
| `title` | `string` | Yes | No | 관광지 / 한옥 명칭 |
| `audioTitle` | `string` | Yes | No | 오디오 트랙 제목 |
| `speaker` | `string` | No | Yes | 해설 도슨트 / 성우 이름 |
| `category` | `OdiiCategory` | Yes | No | 분류 ('한옥/고택', '궁궐/역사', '서원/향교' 등) |
| `mapX` | `string` | Yes | No | WGS84 경도 문자열 |
| `mapY` | `string` | Yes | No | WGS84 위도 문자열 |
| `script` | `string` | Yes | No | 타임코드 포함 원본 대본 텍스트 |
| `parsedScript` | `ScriptLine[]` | No | Yes | 초 단위로 파싱된 동기화 자막 배열 |
| `playTime` | `string` | Yes | No | 재생 시간 (초 단위 문자열, e.g. "185") |
| `audioUrl` | `string` | Yes | No | 도슨트 음원 CDN 스트리밍 URL (MP3) |
| `imageUrl` | `string` | Yes | No | 배경/대표 이미지 URL |

---

## 2. Schema: 자막 라인 (`ScriptLine`)

| Field Name | Type | Required | Nullable | Description |
| :--- | :--- | :---: | :---: | :--- |
| `id` | `number` | Yes | No | 자막 순번 (0-indexed) |
| `timeSec` | `number` | Yes | No | 자막 시작 시간 (초 단위 부동소수점) |
| `text` | `string` | Yes | No | 해당 구간 자막 텍스트 |

---

## 3. Implementation Evidence

- **Types**: `src/features/odii-audio/types/odii.types.ts`
- **Script Parser**: `src/features/odii-audio/utils/scriptParser.ts`
- **Store**: `src/features/odii-audio/store/useOdiiAudioStore.ts`
