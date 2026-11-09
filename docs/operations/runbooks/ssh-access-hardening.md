# Lightsail SSH 접근 하드닝 Runbook

이 runbook은 Issue #586의 "Lightsail SSH TCP 22를 검증된 관리자 공인 IP `/32`로 제한" 항목과 비상 접근 경로를 고정한다. 현황과 제약, 실행 절차, 검증 방법을 다룬다.

## 현재 상태와 제약

- 현재 Lightsail 인스턴스(`13.125.191.16`)의 TCP/22는 전면 개방(`0.0.0.0/0`)이다. SSH는 키 전용이며 계정별로 명령이 제한된 deployer/operator 구조를 이미 갖췄지만, 네트워크 계층 제한은 없다.
- **GitHub Actions hosted runner의 공인 IP는 고정되어 있지 않다.** `https://api.github.com/meta`의 `actions` 목록은 IPv4 기준 수천 개 CIDR(2026-11 기준 5,000+)이므로 Lightsail 방화벽에 Actions CIDR allowlist를 넣는 방식은 현실적으로 불가능하다.
- 따라서 "22를 관리자 /32로 제한"과 "CI가 SSH로 배포"를 동시에 만족하려면 **CI·관리자 접속을 아웃바운드 전용 터널로 이전한 뒤 22를 닫는 것**이 유일하게 양립하는 설계다.

## 설계 선택지

1. **아웃바운드 전용 터널(권장)** — Tailscale(ephemeral CI node + MagicDNS) 또는 Cloudflare Tunnel(기존 Cloudflare DNS 재사용, Access service token)로 SSH를 터널 안으로 이동한다. 서버는 오직 아웃바운드 연결만 만들고 Lightsail 방화벽에서 TCP/22를 제거한다(`apply-ssh-firewall.sh close-ssh`). 이 경우 /32 제한을 넘어 **22 노출 자체가 0**이 되어 같은 항목을 더 강하게 충족한다.
2. **관리자 /32 제한 + CI 배포 창 운영(임시)** — `restrict`로 22를 관리자 /32만 허용하고, 배포가 필요할 때만 `open-ssh` → workflow 실행 → `restrict` 복원을 운영자가 수행한다. scheduled production deploy가 창이 닫혀 있으면 실패하므로 임시 수단으로만 쓴다.
3. **현행 유지(비권장)** — 키 전용+제한 명령 계정만으로 버티는 것. 22 포트 스캔·무차별 대입·sshd 0-day 노출이 남는다.

## 도구

`infra/lightsail/ssh-firewall/apply-ssh-firewall.sh`(operator machine, `aws` CLI + `jq` 필요):

```bash
# 현재 규칙 확인
infra/lightsail/ssh-firewall/apply-ssh-firewall.sh --instance <INSTANCE> status

# TCP/22를 관리자 /32로 제한(다른 포트 규칙은 그대로 보존). --yes 없으면 dry-run
infra/lightsail/ssh-firewall/apply-ssh-firewall.sh --instance <INSTANCE> \
  restrict --admin-cidr <ADMIN_IP>/32 --yes

# 비상 창(전면 개방)과 복원
infra/lightsail/ssh-firewall/apply-ssh-firewall.sh --instance <INSTANCE> open-ssh --yes
infra/lightsail/ssh-firewall/apply-ssh-firewall.sh --instance <INSTANCE> \
  restrict --admin-cidr <ADMIN_IP>/32 --yes

# 터널 전환 완료 후 22 완전 폐쇄
infra/lightsail/ssh-firewall/apply-ssh-firewall.sh --instance <INSTANCE> close-ssh --yes
```

- 이 스크립트는 22 규칙만 교체하고 80/443 등 나머지 규칙을 그대로 보존한다.
- Lightsail 콘솔 > Networking > IPv4 Firewall이 rollback 경로다.

## 비상 접근 경로

방화벽 실수로 SSH가 막혀도 **Lightsail 콘솔의 브라우저 SSH(Connect 탭)**는 AWS out-of-band 경로로 동작한다. 이 경로로 방화벽을 되돌리거나 서버를 점검한다. host key 지문finger 대조도 이 콘솔에서 수행한다(Issue #819).

## 제한 적용 후 검증 체크리스트

1. 관리자 IP에서 `ssh` 접속 성공(또는 터널 경로 접속 성공).
2. 관리자 IP가 아닌 곳에서 TCP/22 connect 실패(예: `nc -z -G 8 13.125.191.16 22`가 실패).
3. CI 배포 경로 1회 성공: staging `deploy_staging=true` 수동 실행 또는 다음 scheduled production deploy.
4. Lightsail 콘솔 브라우저 SSH 접속 성공(비상 경로 확인).
5. 결과를 Issue #586에 기록한다.

## host key 변경 대응

인스턴스 재생성·재설치로 SSH host key가 바뀌면 CI known_hosts 변수(`PRODUCTION_SSH_KNOWN_HOSTS`, `STAGING_SSH_KNOWN_HOSTS`)가 모두 깨진다. 절차:

1. Lightsail 콘솔 브라우저 SSH에서 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`로 지문을 확인한다.
2. 운영자 로컬 `ssh-keyscan <IP>` 결과의 지문과 대조한다. 일치해야만 변수를 갱신한다.
3. 두 환경 변수를 같은 형식(주석 배너 + `ssh-ed25519` 한 줄)으로 갱신한다.
