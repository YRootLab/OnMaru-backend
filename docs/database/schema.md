# Database Schema

이 문서는 온마루(OnMaru) 프로젝트의 데이터베이스 스키마 구조를 정의합니다.
아래의 DBML 코드를 복사하여 [dbdiagram.io](https://dbdiagram.io/)에 붙여넣으면 시각화된 ERD(관계도)를 볼 수 있습니다.

```dbml
// 1. 관광지 (Tour Spot) 테이블
Table tour_spots {
  tid varchar [note: '관광지 ID']
  tlid varchar [note: '관광지 언어 ID']
  lang_code varchar [note: '언어 코드']
  theme_category varchar [note: '테마 카테고리']
  title varchar [note: '관광지명']
  addr1 varchar [note: '주소 1 (시/도)']
  addr2 varchar [note: '주소 2 (시/군/구)']
  map_x varchar [note: '경도 (X 좌표)']
  map_y varchar [note: '위도 (Y 좌표)']
  lang_check varchar [note: '언어 제공 여부 체크']
  image_url varchar [note: '대표 이미지 URL']
  created_time varchar [note: '데이터 생성일시']
  modified_time varchar [note: '데이터 수정일시']
  sync_status varchar [note: '동기화 상태 (A, U, D)']

  indexes {
    (tid, tlid) [pk]
  }
}

// 2. 오디오 가이드 (Audio Guide / Story) 테이블
Table audio_guides {
  stid varchar [note: '이야기 ID']
  stlid varchar [note: '이야기 언어 ID']
  tid varchar [note: '관광지 ID (FK)']
  tlid varchar [note: '관광지 언어 ID (FK)']
  lang_code varchar [note: '언어 코드']
  title varchar [note: '콘텐츠 제목']
  audio_title varchar [note: '오디오 제목']
  script text [note: '오디오 도슨트 대본/자막']
  play_time varchar [note: '재생 시간 (초)']
  audio_url varchar [note: '오디오 URL']
  image_url varchar [note: '대표 이미지 URL']
  map_x varchar [note: '경도 (X 좌표)']
  map_y varchar [note: '위도 (Y 좌표)']
  created_time varchar [note: '데이터 생성일시']
  modified_time varchar [note: '데이터 수정일시']
  sync_status varchar [note: '동기화 상태 (A, U, D)']

  indexes {
    (stid, stlid) [pk]
  }
}

// 관계 (Relationships)
Ref: tour_spots.(tid, tlid) < audio_guides.(tid, tlid)
```
