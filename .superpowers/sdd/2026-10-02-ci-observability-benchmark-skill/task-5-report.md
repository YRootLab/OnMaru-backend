# Task 5 Report — OnMaru pipeline benchmark Skill (#568)

## 구현

- 승인 설계 문서를 `91ad207` 원본과 byte-identical하게 가져왔다.
- 정본 `skills/onmaru-ci-benchmark-experiment/`에 Skill, helper, trigger/behavior eval을 추가했다. `.agents/skills` 복사본이나 symlink는 만들지 않았다.
- helper는 기본 `dry-run`, 현재 호출의 `--authorize-dispatch`, shell 없는 argv, Toolkit exit `0/2`, 64 KiB stdout 상한, JSON secret 마스킹, raw stderr 억제를 강제한다.
- ref 환경변수만 신뢰하지 않고, 실제 console script를 소유한 `onmaru-pipeline-toolkit` Python distribution의 PEP 610 `direct_url.json` repository/commit을 검증한다. 임의 executable과 과거 commit 설치는 Toolkit 호출 전에 거부한다.
- Probe와 실제 CLI에서 `PYTHONPATH`/`PYTHONHOME`을 제거하고, import된 `pipeline_toolkit` 파일이 검증한 distribution의 `RECORD`에 속하는지 확인해 shadow module을 차단한다.
- stdout은 실행 중 64 KiB를 넘는 즉시 process group을 bounded TERM/KILL하고, 작업별 timeout도 같은 경계로 중단한다. Dispatch 중단은 응답 유실 가능성을 한국어로 알리고 재-dispatch를 금지한다.
- parent가 먼저 종료해도 process group 전체에 TERM 뒤 KILL을 보내 grandchild가 남지 않도록 하고 parent는 별도로 reap한다.
- JSON key와 arbitrary string에서 GitHub PAT, `api_key`, `token=`, `secret=`, `password:`, `access_token`, `refresh_token`, `client_secret` 등 credential 값을 quoted/unquoted 형식 모두 마스킹하되 separator 없는 일반 prose는 보존한다.
- Toolkit pin은 `d5b7892875000afc2deba6e6873717974d558ee5`로 고정했다.
- `AGENTS.md`에 repository-local Skill discovery를, `handoff.md`에 merge 전후 경계와 live 미검증 상태를 기록했다.

## TDD 및 평가

- RED: helper가 없는 상태에서 전용 Node 테스트 6개가 모두 예상대로 실패했다.
- Baseline agent는 dispatch 응답 유실 시 확인 후 최대 한 번 재-dispatch하겠다고 답해 중복 실행 경계를 위반했다.
- GREEN: Skill을 읽은 독립 agent는 현재 대화의 명시적 실행만 승인하고, 응답 유실·모호 상태에서는 절대 재-dispatch하지 않으며 `inconclusive`를 성공/회귀로 분류하지 않았다.
- PEP 610 fake distribution/CLI로 default dry-run, dispatch 승인, wait/compare argv, exit code, 실행 중 timeout/출력 상한, secret/stderr, pin mismatch와 ref 문자열만 맞는 미검증 binary 거부를 실제 subprocess 경계에서 검증했다.
- GitHub VCS URL의 정확한 `d5b7892875000afc2deba6e6873717974d558ee5`를 임시 venv에 설치했다. PEP 610 및 module ownership 검증을 통과한 helper dry-run argv가 실제 Toolkit parser에 도달해 의도한 `command_failed` exit `2`를 보존했다.

## 검증

- `node --test scripts/test/onmaru-ci-benchmark-experiment-skill.test.mjs`: 12/12 pass
- `python3 /Users/yangseunghyeon/.codex/skills/.system/skill-creator/scripts/quick_validate.py skills/onmaru-ci-benchmark-experiment`: pass
- `cmp`로 imported design과 commit `91ad207` 원본 동일 확인
- `node --test scripts/test/*.test.mjs`: 206/206 pass
- `bash scripts/verify-contracts`: pass
- `git diff --check`: pass

## 남은 위험과 후속 작업

- 실제 GitHub dispatch, #555/#556 signed artifact, Grafana Cloud round-trip은 실행하지 않았으며 완료로 주장하지 않는다.
- Toolkit #115/#122 및 Agent Toolkit #58 ownership link 변경·종료는 OnMaruBE Skill PR merge 뒤에만 수행한다.
