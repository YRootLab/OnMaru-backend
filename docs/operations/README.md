# Operations

수집 dataset의 원자 게시, run 복구, timeout, retry, 관측성과 운영 검증 조건을 다룬다. 운영 환경이 이미 구축됐다는 뜻은 아니다.

- [Runtime and reliability](runtime-and-reliability.md): sync lease/fence/LKG, journey run lifecycle, 실패·복구·관측성
- [Content tags](content-tags.md): 자체 추출 태그 lexicon 튜닝, 품질 경고, 검증 기준
- [VisitReview moderation](moderation.md): 신고 triage, 고위험 임시 숨김, SLA, Grafana alert, operator drill
- [DataLab 지역 원천 코드](datalab-region-source-codes.md): 공식 코드 검증·registry 상태·staging smoke와 복구 절차
- [Secret loading runbook](runbooks/secrets.md): server-only secret loading, rotation overlap, log redaction drill
- [PostgreSQL restore runbook](runbooks/restore.md): encrypted full backup, isolated restore, deletion ledger replay, RPO/RTO evidence
- [Deprecated: Render·Neon → Lightsail 전환 이력](runbooks/lightsail-render-cutover.md): 완료된 과거 cutover 의사결정과 절차 보존용 문서
- [Lightsail 운영 및 Spring Blue-Green CD](../../infra/lightsail/README.md): 1GB 호스트의 제한된 Blue-Green 배포, 분리된 DB 역할, TLS, rollback, backup 절차

구현 전 게이트: hosting, secret manager, scheduler, 실제 rate limit, restore drill, alert/trace 보존 정책을 확정한다.
