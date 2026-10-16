# 온기 후기 시드 데이터 (Warmth Reviews Seed)

지도마루 온기 모드 화면의 시연 및 초기 사용자 경험을 위해 실제 2030 여행자 톤의 온기 후기와 가상 유저 프로필을 생성하여 DB에 적재하는 스크립트 모음입니다.

## 파일 구성

### 1. 전국 1,080곳 한옥 대상 2,000건 대규모 시드 (Issue #765 / 최신 권장)
- `all_hanoks_1080.json`: 온마루 등록 전체 한옥 1,080곳 메타데이터 (전국 17개 시도 고른 분포)
- `generate_reviews_2000.py`: 전국 1,080곳 대상 2,000건 리얼 여행자 경험/지역별 후기 및 150명 가상 유저 생성기
- `seed-warmth-reviews-2000.sql`: 2,000건 적재 SQL (`54500700-0000-4000-9000-000000000501` ~ `2500`)
- `rollback-warmth-reviews-2000.sql`: 2,000건 시드 및 가상 유저 150명 전용 격리 롤백 SQL

### 2. 초기 300건 + 권역별 200건 시드 (히스토리)
- `places.json`: 운영 API 추출 대표 명소/랜드마크 406곳
- `regional_places.json`: 8대 권역별 10곳 내외 대표 장소
- `seed-warmth-reviews-300.sql`: 초기 300건 시드 SQL
- `seed-additional-200.sql`: 추가 200건 시드 SQL
- `rollback-warmth-reviews-300.sql`: 초기 시드 롤백 SQL

## 안전성 및 격리
- 모든 시드 데이터는 `54500700-*` 고유 UUID 네임스페이스를 사용하므로 실제 사용자가 등록한 후기나 회원 데이터에 전혀 영향을 주지 않습니다.
- 롤백이 필요할 경우 각각의 롤백 SQL을 실행하면 해당 시드 레코드만 삭제됩니다.

## DB 적용 방법

### 1. 운영/스테이징 Lightsail 서버에서 2,000건 적용 시
```bash
# 서버 접속 후 Docker 컨테이너의 PostgreSQL에 실행
docker exec -i lightsail-postgres-1 psql -U onmaru_migration_login -d onmaru < scripts/seed-reviews/seed-warmth-reviews-2000.sql
```

### 2. 로컬에서 원격 PostgreSQL로 실행 시
```bash
psql "postgresql://[USER]:[PASSWORD]@[HOST]:[PORT]/[DB]" -f scripts/seed-reviews/seed-warmth-reviews-2000.sql
```
