# Task 5 Report — OnMaru pipeline benchmark Skill (#568)

## 구현

- 승인 설계 문서를 `91ad207` 원본과 byte-identical하게 가져왔다.
- 정본 `skills/onmaru-ci-benchmark-experiment/`에 Skill, helper, trigger/behavior eval을 추가했다. `.agents/skills` 복사본이나 symlink는 만들지 않았다.
- helper는 기본 `dry-run`, 현재 호출의 `--authorize-dispatch`, shell 없는 argv, Toolkit exit `0/2`, 64 KiB stdout 상한, JSON secret 마스킹, raw stderr 억제를 강제한다.
- Toolkit pin은 `9c6f0033a5ec2429085b29d56ebdb3caca94bbcd`로 고정했다.
- `AGENTS.md`에 repository-local Skill discovery를, `handoff.md`에 merge 전후 경계와 live 미검증 상태를 기록했다.

## TDD 및 평가

- RED: helper가 없는 상태에서 전용 Node 테스트 6개가 모두 예상대로 실패했다.
- Baseline agent는 dispatch 응답 유실 시 확인 후 최대 한 번 재-dispatch하겠다고 답해 중복 실행 경계를 위반했다.
- GREEN: Skill을 읽은 독립 agent는 현재 대화의 명시적 실행만 승인하고, 응답 유실·모호 상태에서는 절대 재-dispatch하지 않으며 `inconclusive`를 성공/회귀로 분류하지 않았다.
- fake Toolkit CLI로 default dry-run, dispatch 승인, wait/compare argv, exit code, secret/stderr, 출력 상한과 pin mismatch를 실제 subprocess 경계에서 검증했다.

## 검증

- `node --test scripts/test/onmaru-ci-benchmark-experiment-skill.test.mjs`: 6/6 pass
- `python3 /Users/yangseunghyeon/.codex/skills/.system/skill-creator/scripts/quick_validate.py skills/onmaru-ci-benchmark-experiment`: pass
- `cmp`로 imported design과 commit `91ad207` 원본 동일 확인
- `node --test scripts/test/*.test.mjs`: 199/199 pass
- `bash scripts/verify-contracts`: pass
- `git diff --check`: pass

## 남은 위험과 후속 작업

- 실제 GitHub dispatch, #555/#556 signed artifact, Grafana Cloud round-trip은 실행하지 않았으며 완료로 주장하지 않는다.
- Toolkit #115/#122 및 Agent Toolkit #58 ownership link 변경·종료는 OnMaruBE Skill PR merge 뒤에만 수행한다.
