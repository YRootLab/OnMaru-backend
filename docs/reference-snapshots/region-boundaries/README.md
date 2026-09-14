# 행정구역 경계 source qualification snapshot

이 폴더는 좌표 resolve와 지역 관측 연결에 사용할 행정구역 경계 자료의 출처·권리·revision 근거를 고정한다.

현재 구현은 실제 대용량 GeoJSON을 저장소에 포함하지 않고, 운영 import 입력이 아래 manifest의 조건을 만족해야 active boundary revision으로 게시될 수 있도록 domain service에서 검증한다.

## 검증 기준

- source URL, license, attribution, observedAt, revisionHash가 모두 있어야 한다.
- geometry는 WGS84 좌표계의 닫힌 MultiPolygon ring이어야 한다.
- 대한민국 좌표 범위를 벗어난 geometry는 quarantine한다.
- 시·군·구는 같은 revision 안의 유효한 시·도 부모 코드를 가져야 한다.
- 동명 지역은 `regionCode`와 `parentRegionCode`로 구분한다.
