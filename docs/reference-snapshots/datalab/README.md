# DataLab 관광 관측 source qualification snapshot

이 폴더는 관광 관측 adapter가 사용하는 DataLab 응답의 출처, 단위, 결측 처리 정책을 기록한다.

현재 구현은 실제 provider credential이나 원본 request URL을 저장하지 않고, adapter mapper가 받은 응답 payload를 typed observation으로 변환하는 계약을 고정한다.

## 정책

- 방문자 수는 지역/일자 단위 과거 관측값이며 실시간 장소 인원으로 쓰지 않는다.
- `basisDate`, `spatialLevel`, `provider`, `unit`, `coverageStatus`를 함께 보존한다.
- 결측 방문자 수는 `0`으로 대체하지 않고 `value: null`, `coverageStatus: NOT_AVAILABLE`로 표현한다.
- provider target을 canonical place로 연결하지 못하면 `UNKNOWN` target으로 보존한다.
