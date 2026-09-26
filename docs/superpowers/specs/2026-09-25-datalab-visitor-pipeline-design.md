# DataLab 방문자 관측 자동 파이프라인 설계

## 목표

후기 장소의 `regionCode`에 대응하는 최신 DataLab 방문자 수를 PostgreSQL에 지속적으로 저장하고, VisitReview 응답의 nullable `visitorCount`로 제공한다. production에서 in-memory 저장소는 사용하지 않는다.

## 흐름

매일 03:30 KST 스케줄러가 DataLab 관측 데이터를 수집한다. adapter는 필수 식별자, 기준일, 값 범위, coverage를 검증하고 유효한 관측만 `insights_visitor_observations`에 revision·region·기준일 단위로 upsert한다. 일시적 네트워크 오류는 제한된 재시도 후 실패를 로그·metric으로 남기며, 이전 정상 관측값은 삭제하지 않는다.

VisitReview 목록 조회는 페이지에 포함된 서로 다른 `regionCode`를 모아 한 번의 JDBC query로 최신 `COMPLETE` 방문자 관측을 조회한다. 장소별 실시간 인원으로 해석하지 않으며, 관측이 없거나 `NOT_AVAILABLE`이면 `visitorCount: null`이다.

## 운영 경계

production은 JDBC observation/query store와 DataLab client가 모두 있어야 시작한다. DB 또는 외부 수집 실패를 in-memory fallback으로 숨기지 않는다. 테스트 profile에서만 deterministic in-memory fixture를 사용한다.

## 검증

PostgreSQL Testcontainers에서 upsert, 재시작 후 최신값 조회, 누락값의 null 매핑을 검증한다. scheduler는 fake DataLab client로 성공·재시도 소진·기존 정상값 보존을 검증한다.
