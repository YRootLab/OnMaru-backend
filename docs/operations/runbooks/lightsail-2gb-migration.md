# 운영 Lightsail $7/1GB → $12/2GB 인스턴스 마이그레이션

> 상태: **작업 예정, 미실행**. 예정 창은 **2026-10-23 금요일 오후(KST)**이며 정확한 시작·종료 시각은 작업 전에 기록한다. 운영 서버 변경, 스냅샷 생성, 고정 IP 이동은 이 문서를 작성하는 작업에 포함되지 않는다.

## 목적과 범위

현재 `onmaru-prod-seoul` 1GB/2 vCPU/40GB Linux 공개 IPv4 인스턴스를 같은 리전의 2GB/2 vCPU/60GB Linux 공개 IPv4 **$12/월 번들 새 인스턴스**로 옮긴다. Spring·PostgreSQL/PostGIS·Nginx·향후 FastAPI/pgvector/BM25/임베딩/reranker를 한 호스트에 두기 위한 용량 변경이다. 이번 인스턴스 이전만으로 RAG 구성이나 모델이 설치·검증되는 것은 아니다. [AWS 번들 표](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-bundles.html)와 실제 서울 리전 콘솔의 선택 가능 번들·가격을 작업 직전에 다시 대조한다.

Lightsail은 기존 인스턴스의 플랜을 제자리에서 바꾸지 않는다. [AWS 플랜 변경 절차](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-right-size-instance.html)에 따라 **기존 인스턴스 스냅샷 → 새 2GB 인스턴스 생성 → 검증 → 고정 IP 재연결** 순서로 진행한다. 이전 중 두 인스턴스가 존재하는 시간은 각각 시간 단위로 과금되며 중지한 기존 인스턴스도 삭제 전까지 과금된다. 수동 스냅샷 저장비도 별도다. AWS 크레딧 적용·최종 청구액은 계정 Billing에서 확인한다. [AWS 청구 안내](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-frequently-asked-questions-faq-billing-and-account-management.html), [스냅샷 요금](https://docs.aws.amazon.com/lightsail/latest/userguide/amazon-lightsail-faq-snapshots.html).

## 작업 전날까지 확인

1. Lightsail 서울 리전에서 현재 인스턴스·AZ, `onmaru-prod-seoul-ip` 고정 IP 연결, 현재 번들·실제 디스크 사용량과 새 2GB/60GB 번들 선택 가능 여부를 기록한다. 현재 `api` DNS가 고정 IP를 가리키면 IP만 옮기고 DNS는 유지한다. 고정 IP가 없다면 DNS 변경과 TTL을 별도로 계획한다.
2. 현재 `master` 배포 image digest, Compose 설정·볼륨·시크릿 경로, systemd timer/cron, 방화벽 규칙, Certbot 인증서·갱신 timer, Grafana 경보와 backup 상태를 기록한다. 새 인스턴스에서는 **사용자 지정 Lightsail 방화벽 규칙이 스냅샷에서 복사되지 않으므로** 필요한 22/80/443 규칙과 SSH `/32` 제한을 재설정한다. [AWS 스냅샷에서 인스턴스 생성](https://docs.aws.amazon.com/lightsail/latest/userguide/lightsail-how-to-create-instance-from-snapshot.html).
3. [PostgreSQL 백업 절차](../../../infra/lightsail/README.md#db-backup과-관측)로 최신 논리 dump를 만들고 checksum·`pg_restore --list`를 확인한다. dump를 **기존 서버 밖**의 접근 제한 저장소에 복사하고 격리 복원 결과를 확인한다. 인스턴스 스냅샷만을 DB 백업으로 간주하지 않는다.
4. 정확한 작업 시작·종료 시각, 예상 서비스 중단, 작업자·검증자, 신규 인스턴스 이름, 이전/신규 인스턴스 ID, rollback 보관 기한을 작업 기록에 적는다. 자동 배포·수집 cron·쓰기 job의 정지 방법을 실제 명령으로 미리 확인한다.

## 금요일 오후 실행 순서

1. **쓰기 정지:** 앱을 점검 모드로 전환하고 새 회원 요청, 관리자 변경, 동기화·백업 외 쓰기 job, 자동 배포를 멈춘다. 진행 중인 run과 DB transaction을 끝내고 최종 데이터 건수·시각을 기록한다. 이 시점부터 새 인스턴스가 준비될 때까지 원본에 쓰기가 생기지 않게 한다.
2. **최종 논리 백업·정상 종료:** 최종 PostgreSQL custom dump와 checksum을 서버 밖에 보관한다. Spring/FastAPI/worker를 중지하고 PostgreSQL을 정상 종료한 뒤 인스턴스를 중지한다. 원본 볼륨을 삭제하거나 초기화하지 않는다.
3. **스냅샷:** Lightsail 콘솔에서 기존 인스턴스의 수동 스냅샷을 만들고 완료 상태를 확인한다. 스냅샷 이름·시각을 기록한다. `Snapshots → Manual snapshots → ⋮ → Create new instance`에서 같은 서울 리전, 가능하면 같은 AZ의 **2GB/$12 번들**과 새 이름을 선택한다. 서울 리전 번들·요금은 화면에서 다시 확인한다. [AWS 생성 절차](https://docs.aws.amazon.com/lightsail/latest/userguide/lightsail-how-to-create-instance-from-snapshot.html).
4. **전환 전 검증:** 새 인스턴스의 임시 접속 경로로 SSH 접속한다. 사용자 지정 Lightsail 방화벽·호스트 방화벽·시크릿 권한·볼륨·Docker/Compose·systemd를 확인한다. 자동 배포/cron을 아직 켜지 않는다. PostgreSQL 기동·`pg_isready`, DB role/extension, dump 시각 대비 행 수·핵심 API, Nginx/TLS 인증서 경로, 내부 네트워크, `free -h`·RSS·디스크를 확인한다. 옛 인스턴스는 중지 상태로 유지한다.
5. **고정 IP 전환:** 새 인스턴스가 통과하면 `Networking → Static IPs`에서 기존 `onmaru-prod-seoul-ip`를 새 인스턴스에 연결한다. 같은 리전의 인스턴스에만 연결할 수 있다. 고정 IP를 재사용하면 기존 DNS A record는 원칙적으로 그대로다. TLS의 도메인·인증서, 공개 HTTPS health·인증·읽기 API·쓰기 테스트·SSE 연결/재연결, Grafana trace/metric·백업 job을 실제 공개 경로에서 검사한다. [AWS 고정 IP](https://docs.aws.amazon.com/lightsail/latest/userguide/understanding-static-ip-addresses-in-amazon-lightsail.html).
6. **운영 재개:** 합격한 뒤에만 쓰기와 자동 배포·수집 job을 재개한다. 새 인스턴스에서 첫 백업을 만들고 외부 저장·복원 가능성을 확인한다. CPU뿐 아니라 `MemAvailable`, swap/OOM, Spring/DB/Nginx RSS, 기존 API p95를 전환 후 추적한다. 향후 RAG용 FastAPI/모델 추가 시에는 [2GB 모델 포함 혼합 부하 게이트](https://github.com/YRootLab/onmaru-ai-engine/blob/00847c174043ccf6d542a6ac15c595556ce8da3f/docs/ai-rag/operations-and-evaluation.md)를 별도로 통과해야 한다.

## 중단과 롤백

- **고정 IP 전환 전 실패:** 새 인스턴스의 서비스를 멈추고 원본 인스턴스를 다시 시작해 점검 모드를 해제한다. 원본에 새 쓰기가 없었는지 확인한다.
- **전환 후 쓰기 재개 전 실패:** 새 인스턴스를 점검 모드로 두고 고정 IP를 원본에 다시 연결한 뒤 원본을 시작해 검증한다. 양쪽에서 동시 쓰기를 허용하지 않는다.
- **새 인스턴스에서 쓰기 재개 후 실패:** 원본 DB는 과거 시점이므로 **IP만 원본으로 되돌리면 데이터가 손실된다.** 먼저 쓰기를 다시 멈추고 새 인스턴스의 최신 논리 백업·복구 또는 변경분 재적용 계획을 검증한 뒤 복귀한다. 복구 경로가 없다면 공개 쓰기를 닫고 원인 복구를 우선한다.

전환 성공 직후 원본 인스턴스와 스냅샷을 즉시 삭제하지 않는다. rollback 보관 기한과 새 서버의 백업/복원 성공을 확인한 다음 삭제 여부를 결정하며, 남겨 둔 기간의 중복 인스턴스·스냅샷 비용을 Billing에서 확인한다. 완료 기록에는 인스턴스/스냅샷 ID, 고정 IP 전환 시각, 검증 명령·결과, 새 backup ID, 변경 전후 Grafana 기준선, 문제가 있었을 때의 조치와 rollback 결과를 남긴다.
