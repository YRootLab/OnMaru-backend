# 장소 선별·K-Contents 기획

이 디렉터리는 OnMaru의 TourAPI 장소 선별, 전통문화 탐색 주제, 작품–장소 촬영 관계와 근거 기반 라벨링 정책을 함께 관리한다. 공개 화면의 UI 구성은 FE가 결정하며, 여기서는 데이터·API에 필요한 의미와 품질 경계를 정의한다.

- [통합 PRD](product-prd.md): 후보 수집·의미 판정·증분 동기화·K-Contents 조사/검증·공개 API 정책. 공개 탐색 주제와 자유 태그의 정규화 정책도 이 문서가 정본이다.
- [#681 실데이터 정책 검증](policy-validation.md): 34개 코드와 구제 검색의 실제 페이지·후보/기존 공개 교차 집계, `discovery-candidate-v1.0.0`, legacy API 기준선.
- [FE 신규 API 명세 초안](../../contracts/discovery-kcontents-api.md): 기존 API를 보존하며 추가할 전통문화 탐색·K-Contents 6개 읽기 경로와 호환성·페이지네이션 계약.
- [백엔드 구현 Issue Graph](backend-implementation-issues.md)과 [기계 검증용 Work Graph](work-graph.json): 기존 [#603](https://github.com/YRootLab/OnMaru-backend/issues/603)을 Root로 재사용하고 11개 Child Issue를 발행했다. 선행 관계, 병렬 Wave, 인수 기준과 통합 gate를 추적한다.

현재 문서는 구현 전 검토안이며, 기존 ADR·Issue·운영 API 계약을 자동으로 대체하지 않는다. 후속 세부 문서를 만들 때에는 이 디렉터리에서 주제별로 분리하고 PRD와의 관계를 명시한다.
