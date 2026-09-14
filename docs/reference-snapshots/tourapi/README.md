# TourAPI qualification snapshot

이 디렉터리는 Issue #66의 redacted qualification manifest다. 원천은
한국관광공사 `국문 관광정보 서비스_GW`이며, 공공데이터포털 데이터셋 ID는
`15101578`이다.

## 확인한 계약

- Base URL: `https://apis.data.go.kr/B551011/KorService2`
- 응답 형식: JSON/XML. OnMaru adapter는 JSON을 우선 사용한다.
- 개발계정 기본 트래픽: 일 1,000건. 운영계정은 활용사례 등록 후 트래픽 증설 신청.
- 이용 조건: 무료, 이용허락범위 제한 없음.
- 이미지: 공공누리 1유형/3유형 이미지가 섞이며, 사진 자료는 명예훼손·인격권 침해·일반 정서 위반 용도와 기업 CI/BI 이용을 금지한다.
- 공통 성공 envelope: `response.header.resultCode == "0000"`을 성공으로 본다.
- 인증/권한/GW 오류는 HTTP status와 별도로 `OpenAPI_ServiceResponse.cmmMsgHeader` 또는 `response.header`에 나타날 수 있다.
- #66 snapshot은 `areaBasedList2`, `locationBasedList2`, `detailCommon2`, `searchKeyword2`, `areaCode2` operation을 포함한다.

## Fixture 경계

`testing/fixtures/provider/tourapi`에는 파서와 retry classifier가 필요한 shape만
저장한다. 서비스 키, 원본 query string, 추적 ID, provider key를 포함할 수 있는
header는 저장하지 않는다.

## LIVE_CANONICAL 판정

Issue #73의 HTTP client와 envelope parser는 이 manifest의 모든 fixture를 typed
parse하거나 명시 오류로 분류해야 한다. 이 검증이 통과하기 전까지 운영 공개는
`FIXTURE` 또는 `VERIFIED_SNAPSHOT` 모드만 허용하고, parser 통과 뒤
`LIVE_CANONICAL`을 켠다. 따라서 `manifest.json`의 `liveCanonicalBlocked` 값은
Issue #73 완료 전까지 `true`다.
