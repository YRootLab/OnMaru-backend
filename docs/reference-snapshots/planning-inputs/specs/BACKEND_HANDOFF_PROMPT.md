# 💌 백엔드 엔지니어 / AI를 위한 온마루(OnMaru) 설계 가이드 & 프롬프트

안녕하세요 백엔드 개발자님! 🇰🇷  
이 문서는 **프론트엔드(FE) 코드를 일일이 분석하지 않고도**, `docs/specs/` 디렉토리의 내용만으로 온마루 백엔드(Spring Boot / RDBMS)를 빠르고 유연하게 설계·구현하실 수 있도록 돕기 위해 작성되었습니다.

> 💡 **안내**: 이 가이드는 특정 아키텍처나 라이브러리를 **강제하지 않습니다.**  
> 팀의 백엔드 컨벤션과 최선의 엔지니어링 판단에 따라 자유롭게 최적화하여 설계해 주세요!

---

## 🧭 FE 코드를 안 보셔도 되는 이유 (이미 다 정리되어 있습니다!)

"FE가 관광공사 TourAPI의 어떤 API를 호출했고, 파라미터로 뭘 넘겼는지"를 찾기 위해 FE 리액트 코드를 뒤적거리실 필요가 전혀 없습니다.

| 궁금한 점 | 바로 확인하실 스펙 문서 경로 |
| :--- | :--- |
| **관광공사 TourAPI 중 어떤 API와 카테고리를 썼는가?** | 👉 [`integrations/tourapi-adapter.md`](integrations/tourapi-adapter.md) |
| **공공데이터 필드가 FE DTO로 어떻게 변환되었는가?** | 👉 [`data-contracts/hanok-village.md`](data-contracts/hanok-village.md) |
| **FE가 호출하고 싶어 하는 표준 REST API 규격은?** | 👉 [`backend-requirements/`](backend-requirements/) |
| **도메인 모델의 통합 표준(Place)은 무엇인가?** | 👉 [`data-contracts/place-canonical.md`](data-contracts/place-canonical.md) |
| **전체 화면-기능-데이터-API의 연결 관계는?** | 👉 [`traceability/fe-be-traceability-matrix.md`](traceability/fe-be-traceability-matrix.md) |

---

## 🤖 백엔드 AI / 개발자 전달용 복사 프롬프트 (Copy & Paste)

백엔드 레포지토리에서 AI(Claude, Gemini, ChatGPT 등)나 동료 개발자에게 작업을 시작할 때 아래 프롬프트를 복사해서 넘겨주시면 됩니다:

```markdown
안녕하세요! 우리는 전통 한옥 및 문화유산 인터랙티브 웹 서비스 "온마루(OnMaru)"의 백엔드를 Spring Boot 기반으로 구축하고 있습니다.

프론트엔드에서 분석한 화면별 기능 및 공공데이터 연동 요구사항이 `docs/specs/` 디렉토리에 정리되어 있습니다.
프론트엔드 코드를 직접 분석하실 필요 없이, 아래 순서와 원칙을 참고하여 자유롭고 유연하게 백엔드 아키텍처를 설계해 주세요.

### 📋 참고할 핵심 스펙 문서
1. `docs/specs/backend-requirements/`: FE가 요구하는 표준 API 엔드포인트 후보 (한옥 도감, 지도, 온기 피드, 오디 오디오)
2. `docs/specs/integrations/tourapi-adapter.md`: 한국관광공사 TourAPI 4.0 및 Odii API 연동 정보 (사용한 오퍼레이션, 파라미터, 장애 대응)
3. `docs/specs/data-contracts/place-canonical.md`: 통합 장소 엔티티(Place) 정규화 모델
4. `docs/specs/traceability/fe-be-traceability-matrix.md`: 전체 화면과 기능 간 추적 매트릭스

### 💡 권장 및 고려사항 (자유롭게 최적화해 주세요)
- **외부 API 통신 & 캐싱**: 공공데이터 포털(TourAPI)의 응답 지연과 쿼터 제한을 고려하여, OpenFeign/WebClient 통신 및 Redis 캐싱(또는 Spring Batch를 통한 주기적 DB 적재) 전략을 유연하게 제안해 주세요.
- **도메인 엔티티**: `place-canonical.md`를 참고하여 JPA Entity를 모델링하되, 프로젝트 규모에 맞게 테이블과 관계를 자유롭게 조정해 주세요.
- **에러 핸들링**: 공공데이터 장애 시에도 프론트엔드로 에러가 바로 터지지 않도록 Stale-While-Revalidate 또는 DB 스냅샷 반환 구조를 고려해 주시면 좋습니다.

우선 1단계로 [한옥 도감 API (BE-REQ-001~003)] 및 [TourAPI 연동 Client] 설계를 먼저 검토해 주실 수 있나요?
```

---

## 🎯 관광공사 TourAPI 연동 퀵 레퍼런스 (FE에서 사용했던 내역 요약)

* **한옥스테이**: `contentTypeId=32`, `cat1=B02`, `cat2=B0201`, `cat3=B02011600`
* **전통마을**: `contentTypeId=12`, `cat1=A02`, `cat2=A0201`, `cat3=A02010800`
* **고택·종택**: `contentTypeId=12`, `cat1=A02`, `cat2=A0201`, `cat3=A02010100`
* **궁궐·누각**: `contentTypeId=12`, `cat1=A02`, `cat2=A0201`, `cat3=A02010300`
* **정렬 조건**: `arrange=P` (인기/조회순 정렬)
* **상세 개요/이용시간**: `detailCommon1` (`overviewYN=Y`), `detailIntro1`
* **오디 오디오 투어**: Odii 전용 스토리 목록 및 MP3 스트리밍 CDN URL
