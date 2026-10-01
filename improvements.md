# improvements.md

아직 Issue로 분리하지 않은 후속 아이디어만 보관합니다. 실행 가능한 작업으로 구체화되면 기존 GitHub Issue를 연결하거나 새 Issue로 승격합니다.

## Product & AI

- [ ] 공모전 시연 시나리오와 근거 자료 준비
  - 분류: 로컬 유지 (needs-triage)
  - 맥락: 심사/외부 소개용으로 핵심 여정과 공공데이터 활용 가치를 보여줄 자료가 필요하다.
  - 다음 판단: 행사 일정과 요구 산출물을 확인한 뒤 독립 Issue로 구체화한다.

- [ ] 두 시대를 비교하는 실험형 여정 UI 검토
  - 분류: 로컬 유지 (needs-triage)
  - 맥락: 역사·문화 이야기를 시대별 지도 경험으로 확장하는 아이디어다.
  - 다음 판단: 핵심 MVP 안정화와 사용자 피드백 이후 범위를 정한다.

## Architecture & Operations

- [ ] 부하 근거를 바탕으로 캐시/그래프 조회 기술 재평가
  - 분류: 로컬 유지 (needs-triage)
  - 맥락: 현재 PostgreSQL과 메모리 캐시로 운영한다. 별도 Redis나 그래프 DB 도입은 실제 병목이 확인된 뒤 판단한다.
  - 다음 판단: 대표 쿼리 latency와 동시성 측정 결과가 생기면 별도 Issue로 승격한다.

## 정리 내역

- Gemini 사용량 정책 메모는 열린 [Issue #518](https://github.com/YRootLab/OnMaru-backend/issues/518)에 범위와 완료 조건이 구체화되어 있어 이 inbox에서 제거했다.
- 브랜치 보호 확인 메모는 완료된 [Issue #252](https://github.com/YRootLab/OnMaru-backend/issues/252)와 현재 `AGENTS.md` 설정 기록으로 완료 확인되어 제거했다.
