# Operations

수집 dataset의 원자 게시, run 복구, timeout, retry, 관측성과 운영 검증 조건을 다룬다. 운영 환경이 이미 구축됐다는 뜻은 아니다.

- [Runtime and reliability](runtime-and-reliability.md): sync lease/fence/LKG, journey run lifecycle, 실패·복구·관측성
- [VisitReview moderation](moderation.md): 신고 triage, 고위험 임시 숨김, SLA, Grafana alert, operator drill

구현 전 게이트: hosting, secret manager, scheduler, 실제 rate limit, restore drill, alert/trace 보존 정책을 확정한다.
