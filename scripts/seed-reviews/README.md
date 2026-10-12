# 온기 후기 300건 시드 데이터 (Warmth Reviews Seed)

지도마루 온기 모드 화면의 시연 및 초기 사용자 경험을 위해 실제 2030 여행자 톤의 온기 후기 300건과 가상 유저 프로필 35명을 생성하여 DB에 적재하는 스크립트입니다.

## 파일 구성
- `places.json`: 운영 API에서 추출한 전국 대표 명소/랜드마크 406곳
- `fetch_places.py`: 명소 목록 수집 스크립트
- `generate_reviews_sql.py`: 85개 주요 랜드마크 대상 300건 리뷰 및 SQL 생성기
- `seed-warmth-reviews-300.sql`: 실제 DB 적재용 실행 SQL (트랜잭션 안전, UUID 네임스페이스 `54500700-*` 사용)
- `rollback-warmth-reviews-300.sql`: 시드 데이터만 깔끔하게 제거하는 롤백 SQL

## 안전성 및 격리
- 모든 시드 데이터는 `54500700-*` 고유 UUID 네임스페이스를 사용하므로 실제 사용자가 등록한 후기나 회원 데이터에 전혀 영향을 주지 않습니다.
- 롤백이 필요할 경우 `rollback-warmth-reviews-300.sql`을 실행하면 해당 시드 레코드만 삭제됩니다.

## DB 적용 방법

### 1. 운영/스테이징 Lightsail 서버에서 직접 실행 시
```bash
# 서버 접속 후 Docker 컨테이너의 PostgreSQL에 실행
docker exec -i lightsail-postgres-1 psql -U onmaru_migration_login -d onmaru < scripts/seed-reviews/seed-warmth-reviews-300.sql
```

### 2. 로컬에서 원격 PostgreSQL로 실행 시
```bash
psql "postgresql://[USER]:[PASSWORD]@[HOST]:[PORT]/[DB]" -f scripts/seed-reviews/seed-warmth-reviews-300.sql
```
