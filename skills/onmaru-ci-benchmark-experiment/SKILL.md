---
name: onmaru-ci-benchmark-experiment
description: Use when working in YRootLab/OnMaru-backend and the user explicitly asks to prepare, dispatch, wait for, or compare an OnMaru CI/test pipeline experiment against develop.
---

# OnMaru CI Benchmark Experiment

OnMaruBE의 pipeline 설정 변경을 고정된 `develop`과 비교할 때만 사용한다. 계산, GitHub 검증, evidence parsing, 판정 정책은 고정 Toolkit CLI에 맡긴다.

## 적용 범위

다음 요청에만 적용한다.

- OnMaru CI/test pipeline 실험 준비 또는 dry-run
- baseline/candidate 각 3회 측정 dispatch
- 정확한 receipt의 run/attempt 대기
- 저장된 collection 비교 및 결과 설명

일반 CI 오류 분석, 막연한 성능 질문, release 승인만 하는 작업, application benchmark, 배포, 다른 저장소에는 적용하지 않는다.

## 실행 경계

동작을 지정하지 않으면 반드시 `dry-run`이다. 일반적인 “개선해줘”, 이전 dry-run, 오래된 대화의 승인으로 실제 실행을 추론하지 않는다.

`dispatch`는 **현재 대화에서 사용자가 실제 실행을 명시적으로 요청한 경우에만** `--authorize-dispatch`를 붙인다. POST 응답이 유실되거나 모호하면 이미 실행됐을 수 있다. **절대 재-dispatch하지 말고** GitHub Actions에서 확인할 run을 안내한 뒤 멈춘다.

## 준비

Python 3.9+, `git`, 인증된 `gh`, 고정 Toolkit CLI가 필요하다. Toolkit은 다음 커밋으로 설치하고 실행 환경에도 같은 ref를 지정한다.

```sh
python3 -m pip install 'git+https://github.com/YRootLab/OnMaru-backend-ci-toolkit.git@d5b7892875000afc2deba6e6873717974d558ee5'
export ONMARU_PIPELINE_TOOLKIT_REF=d5b7892875000afc2deba6e6873717974d558ee5
```

helper는 console script가 사용하는 Python distribution의 `direct_url.json`에서 repository와 commit을 검증한다. ref 환경변수 문자열만 맞는 실행 파일은 거부한다.

후보는 clean하고 push된 `feature/*`여야 한다. 저장소, branch, remote SHA, workflow, integration gate의 최종 판정은 Toolkit이 수행한다.

## 작업 흐름

helper는 shell 없이 Toolkit argv를 구성하고 stdout을 제한·마스킹한다.

```sh
python3 skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py \
  --scope ci --reason 'Gradle worker 비교'
```

Dry-run 결과에서 immutable baseline/candidate SHA, scope, policy, workflow, 표본 수, gate 상태를 보여준다. 실제 실행이 명시 승인된 경우에만:

```sh
python3 skills/onmaru-ci-benchmark-experiment/scripts/run_experiment.py dispatch \
  --authorize-dispatch --scope ci --reason 'Gradle worker 비교'
```

receipt는 working tree 밖에 저장한다. 이후 `wait --receipt <path>`로 그 exact run/attempt만 기다리고, `compare --input <collection>`으로 offline replay한다. 최신 run을 대신 선택하거나 표본을 자동 추가하지 않는다.

## 결과 설명

- exit `2`, 실행 전제 오류: `실행 조건 불충족(<code>)`으로 시작하고 branch/auth/clean-tree/remote/workflow/gate 중 고칠 조건과 dry-run 재확인만 안내한다.
- exit `2`, dispatch 응답 유실·모호: `dispatch 상태 모호(<code>)`로 시작하고 재요청 금지와 Actions 수동 확인을 안내한다.
- exit `2`, attestation/artifact/source/timeout/comparability 오류: `증적 검증 실패(<code>)`로 시작하고 consumer-local evidence 확인을 안내한다. 검증을 우회하거나 evidence를 수정하지 않는다.
- exit `0`, verdict `inconclusive`: 성공도 회귀도 아니다. exclusions와 부족한 증적을 설명하고 자동 재실행·표본 추가·release 승인을 하지 않는다.
- 비교 가능 결과: 개별 측정값, 중앙값, 범위, 상대 변화, 실패율, exclusions, evidence 링크, `online_verified` 또는 `offline_replay` 여부를 구분한다.

자격 증명, raw API 응답, raw stderr, 신뢰하지 않은 artifact 내용은 출력하거나 커밋하지 않는다. 실제 #555/#556 연동은 명시적 live dispatch가 수행되기 전까지 검증됐다고 주장하지 않는다. Toolkit #115/#122와 Agent Toolkit #58의 ownership 링크 변경은 이 Skill이 merge된 뒤에만 진행한다.
