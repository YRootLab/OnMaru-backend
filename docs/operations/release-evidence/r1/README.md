# R1 한옥 카탈로그·공공데이터 동기화 출시 증거

## 게이트 범위

R1 출시 게이트는 한국관광공사 TourAPI 연동, 한옥 데이터셋 파싱 및 정규화, API 키 유출 방지(Redaction), 그리고 Last-Known-Good(LKG) 원자적 리비전 배포 전 과정을 검증합니다.

| 시나리오 | Runtime 증거 | 실패 시 차단하는 결함 |
| --- | --- | --- |
| `tourapi-envelope-parser` | TourAPI 4xx/5xx/200 OK 에러 envelope 정상 파싱 | 비정상 응답 시 프로세스 크래시, 잘못된 성공 처리 |
| `tourapi-key-redaction` | URL 파라미터 및 로그 내 ServiceKey 마스킹 | 공공데이터 API 키 및 토큰 노출 |
| `lkg-atomic-publication` | 데이터셋 검증 완료 후 원자적 활성 포인터 갱신 | 부분 업데이트로 인한 불완전 데이터 노출 |
| `category-quarantine` | 비정상 카테고리/스키마 drift 데이터 격리 | 데이터베이스 스키마 및 검색 인덱스 오염 |

## 재현 명령

```bash
node --test scripts/test/tourapi-fixtures.test.mjs
./gradlew :adapters:tourism-api:test
```

## 판정 기준

- TourAPI 제공자 fixture 전체(정상/오류/빈목록/페이지네이션) 통과.
- URL 또는 JSON fixture 내 민감정보(ServiceKey) 잔존 시 즉시 빌드 실패.
- LKG 데이터셋 리비전이 원자적으로만 교체되는지 확인.
