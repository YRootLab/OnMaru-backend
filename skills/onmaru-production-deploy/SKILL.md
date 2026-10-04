---
name: onmaru-production-deploy
description: OnMaru의 검증된 원격 master를 AWS Lightsail 운영 환경에 지금 배포하며, 사용자가 명시하면 원격 develop을 저장소 Git Flow로 release/master에 승격한 뒤 배포한다. “온마루/AWS/Lightsail 운영 배포해줘”, `$onmaru-production-deploy` 직접 호출, 이 skill의 SKILL.md를 지정한 실행 명령에 사용하고, 설명·검토·상태 조회·스테이징 요청에는 실행하지 않는다.
---

# OnMaru 운영 즉시 배포

GitHub Actions의 수동 production 진입점만 호출한다. 로컬에서 빌드하거나 Lightsail에 직접 SSH하지 않는다. 예약 배포는 repository workflow가 담당하며 이 skill은 예약 시각을 변경하지 않는다.

## 요청 모드 판별

- `$onmaru-production-deploy` 단독 호출, `원격 master 기준으로 운영 배포해줘`, `AWS에 지금 배포해줘`, `Lightsail 운영 배포해줘`는 **배포 전용 모드**다. GitHub의 최신 원격 `master`를 그대로 배포하며 현재 로컬 브랜치는 기준으로 삼지 않는다.
- `원격 develop을 release/master로 승격한 뒤 운영 배포해줘`, `develop 릴리즈하고 AWS 운영 배포까지 해줘`처럼 승격과 배포를 모두 명시하면 **릴리즈 후 배포 모드**다.
- 배포 전용 문구만으로 `develop` 승격, release branch 생성, PR 생성·병합 또는 tag 생성을 추론하지 않는다.
- `AWS`, `Lightsail`, `master`, `develop`이라는 단어만 등장한 설명·질문·검토 요청은 실행 명령이 아니다.

릴리즈 후 배포 모드에서는 먼저 저장소 `AGENTS.md`의 Git Flow와 Release Policy를 따르고, 가능한 경우 `release-orchestration` skill을 사용한다. `origin/develop`을 기준으로 `release/*`를 거쳐 `master`로 보내며 보호 브랜치에 직접 push하지 않는다. 각 PR의 필수 `verify`가 성공해야 다음 단계로 진행한다. 릴리즈가 실패하거나 병합되지 않으면 운영 배포를 시작하지 않는다. `master` 반영 후에는 아래 배포 전용 절차로 이어간다.

## 실행 권한 경계

- 현재 사용자 메시지가 OnMaru **운영 환경에 지금 배포하라는 명시적 명령**일 때만 외부 상태를 변경한다.
- 메시지에 `$onmaru-production-deploy`가 직접 호출되면, 별도 동사가 없어도 최신 검증 원격 `master`를 지금 운영 배포하라는 명령으로 본다. `develop` 승격은 별도로 명시해야 한다.
- 이 skill의 `SKILL.md` 경로 또는 파일명을 언급하면서 `배포해줘`, `실행해줘`, `돌려줘`처럼 실행 동사를 함께 사용한 경우도 같은 명령으로 본다.
- `SKILL.md 보여줘`, `스킬 검토해줘`, `이 스킬이면 어떻게 배포돼?`처럼 파일이나 스킬을 설명·수정·검토하려는 언급은 배포 권한이 아니다.
- `deploy가 뭐야`, `배포 상태 알려줘`, `스테이징 배포`, `나중에 배포하자`처럼 설명·조회·다른 환경·미래 의도인 요청은 dispatch 권한이 아니다.
- 대상 환경이나 즉시 실행 의도가 불명확하면 dispatch하지 말고 한 문장으로 확인한다.
- 한 요청에서 workflow를 한 번만 dispatch한다. 실패하거나 run 식별이 불가능해도 자동으로 두 번째 배포를 만들지 않는다.

## 고정 대상

- Repository: `YRootLab/OnMaru-backend`
- Workflow: `.github/workflows/deploy.yml`
- Ref: `master`
- Input: `deploy_production=true`
- Production URL: `https://api.onmaru.site/auth/csrf`

## 사전 확인

1. `gh auth status`로 GitHub CLI 인증을 확인한다.
2. `gh repo view YRootLab/OnMaru-backend --json defaultBranchRef`에서 기본 브랜치가 `master`인지 확인한다.
3. default branch의 workflow에 `deploy_production` input과 `production-deploy` job이 있는지 읽기 전용으로 확인한다. 배포 전용 모드에서 아직 반영되지 않았다면 중단하고 bootstrap release가 필요하다고 알린다. 릴리즈 후 배포 모드에서는 승격 대상 `origin/develop`에도 같은 진입점이 있는지 먼저 확인하고, `master` 병합 후 다시 확인한다.
4. **Repository Variable** `PRODUCTION_DEPLOY_ENABLED`가 정확히 `true`인지 확인한다. job-level `if`에서 사용하는 값이므로 `production` environment variable로 대체하지 않는다.
5. GitHub `production` environment에 `PRODUCTION_DEPLOY_SSH_KEY`, `PRODUCTION_DEPLOY_WEBHOOK_URL` secret과 `PRODUCTION_SSH_KNOWN_HOSTS` variable의 이름이 등록돼 있는지 확인한다. secret 값은 읽거나 출력하지 않는다.
6. 최신 `master` SHA를 조회하고 사용자에게 배포할 짧은 SHA를 commentary로 알린다. 별도 승인 reviewer는 요청하지 않는다.

## 실행과 관찰

다음과 동등한 GitHub dispatch를 한 번 실행한다.

```bash
gh workflow run deploy.yml \
  --repo YRootLab/OnMaru-backend \
  --ref master \
  -f deploy_production=true
```

dispatch 직전 UTC 시각과 master SHA를 보관한다. 이후 `gh run list`에서 그 시각 이후 생성됐고, event가 `workflow_dispatch`, branch와 head SHA가 대상 master와 일치하는 run을 식별한다. 다른 run을 추측해서 선택하지 않는다.

식별한 run은 완료될 때까지 60초보다 짧은 간격으로 확인하고, 사용자에게 60초를 넘기지 않게 진행 상황을 알린다. `gh run watch`로 장시간 대화를 막지 말고 bounded poll을 사용한다.

## 종료 조건

- 성공: run URL, 배포 SHA와 `production-preflight`, build·scan, migration, `production-deploy`, `notify-production` 결과를 구분해 보고하고 공개 CSRF endpoint가 HTTP 200인지 읽기 전용으로 확인한다. preflight가 `deployed`를 반환했다면 image build와 재기동이 모두 생략된 no-op이라고 구분한다.
- rollback hold: preflight가 `held`로 실패하면 같은 SHA를 자동 재배포하지 않는다. hold를 제거하거나 rollback을 되돌리는 외부 변경을 추론하지 말고, 수정된 새 `master` SHA가 필요하다고 보고한다.
- 실패·취소: 실패한 job과 run URL을 보고한다. transaction 내부 복구 결과만 확인하며 자동 재시도하거나 `rollback_production`을 대신 실행하지 않는다.
- 준비 미완료: 누락된 workflow, repository activation variable, environment secret 또는 host key의 **이름만** 보고하고 중단한다.
- 어떤 경우에도 secret 값, SSH private key, GitHub token을 출력하지 않는다.
