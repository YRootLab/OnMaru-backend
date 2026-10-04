# Project Roadmap

This file is reserved for long-term vision and milestone-level goals. Actionable implementation work should be tracked in GitHub Issues after triage.

## Vision

- Build the OnMaru backend that supports FE requirements, tourism data integrations, map/heat data, Hanok archive features, and audio story experiences.

## Milestones

- Backend architecture and persistence model decision.
- API contract and FE/BE traceability alignment.
- External tourism data ingestion and synchronization design.
- PostgreSQL hosting, authentication, security, and observability decision (provider pending).
- MVP backend implementation and release pipeline hardening.
- 운영 확장 마일스톤: 월 $7 Lightsail(1GB)을 유지하면서 4GB swap을 Spring Blue-Green 배포의 짧은 중첩 구간에만 안전망으로 사용한다. swap을 상시 RAM처럼 계산하지 않으며 지속적인 thrashing, 반복 OOM, 응답 지연 목표 위반이 실제 관측될 때에만 2GB 이상 상향을 재검토한다. 인스턴스 상향은 등록 사용자 수나 예상치가 아니라 메모리·swap·OOM·latency와 부하 테스트 증거로 결정한다.
- Source-grounded FastAPI docent with evaluation, cost controls, and failure isolation.
- Personal memory milestone: show saved places, Odii stories, and saved journeys as a monthly timeline in the member profile.
- Later social organization milestone: save lightweight visit reviews and group saved items into user collections after privacy, moderation, and list-sharing policies are defined.
- Optional future cultural knowledge milestone: add source-grounded Q&A and cultural pages for hanok and traditional culture only after the journey MVP is complete and source licensing, corpus curation, and response safety policies are separately approved.
