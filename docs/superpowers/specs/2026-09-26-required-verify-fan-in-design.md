# Required Verify Fan-out/Fan-in 설계

## 목표와 범위

Issue #368의 직렬 `verify` 기준선을 immutable GitHub Actions artifact로 확정한 뒤, Issue #364에서 기존 `CI / verify` 이름과 실패 의미를 유지하면서 검증을 병렬 lane으로 전환한다. 이 변경은 branch protection, release gate, 배포 workflow를 수정하지 않는다. 장기 develop/nightly/release 추세와 승인 hold는 Issue #366 범위로 남긴다.

## 선행 기준선

직렬 기준선은 같은 develop commit `34276f201ce6f7b5ffb6e7ab2784eb584a91a699`에서 성공한 run `36159816646`, `36160594916`, `36161322635`를 사용한다. 세 `verify` job의 실행 시간은 각각 413초, 361초, 400초이고 중앙값은 400초다.

기본 브랜치 `master`에 등록된 `Collect CI Baseline Evidence` workflow를 위 세 run ID로 dispatch한다. collector는 Actions API에서 run/job/step 정보를 읽고 `ci-serial-baseline-*` artifact를 만든다. CPU와 RSS가 API에 없으면 값을 추정하거나 0으로 기록하지 않고 unavailable 상태를 유지한다. 생성한 artifact와 세 원본 run URL을 #368에 기록하고 #364 PR의 비교 입력으로 연결한다.

## CI 구조

`.github/workflows/ci.yml`은 다음 책임으로 나눈다.

1. `hygiene`는 checkout, repository hygiene, PR branch Issue parser, Node fixture, planning snapshot, Odii fixture manifest를 검증한다.
2. `contract`는 Python public-contract validator와 DBML/generated contract 검증을 실행한다.
3. `module-tests`는 Toolkit v0.1.2의 immutable commit `ff3028ae728de076ea38aa56135529c1566f25a8`에 고정된 reusable module benchmark를 호출한다. consumer-owned `.github/benchmark-modules.yml`, 동일한 `toolkit_ref`, `max_parallel: 4`, `baseline_ref: develop`을 전달한다. PR에서는 affected-module mode, develop/master push와 수동 실행에서는 full-suite mode를 사용한다.
4. `verify`는 위 세 필수 lane을 `needs`로 묶고 `if: always()`로 항상 실행한다. 모든 job result가 `success`이고 Toolkit의 result와 필수 evidence output이 완전할 때만 성공한다.

기존 별도 `Module Benchmark` shadow workflow는 전환 PR에서 비교 가능한 실행 증적을 유지하기 위해 변경하지 않는다. required `CI`가 안정화된 뒤 중복 실행 정리는 #366에서 증적 보존 정책과 함께 다룬다.

## 실패와 skip 처리

최종 `verify`는 실패, 취소, 예기치 않은 skip, Toolkit result 누락, 필수 manifest/report artifact 누락을 성공으로 바꾸지 않는다. 각 `needs.<job>.result`와 Toolkit output을 summary에 명시해 어떤 lane이 종료 결론을 만들었는지 확인할 수 있게 한다.

PR 변경 범위 때문에 실행할 모듈이 없다는 판단은 Toolkit이 명시적인 성공 결과와 완전한 aggregate evidence를 제공할 때만 허용한다. output 또는 artifact가 없으면 허용된 skip으로 추론하지 않고 실패시킨다. 공통 설정, workflow, 인식되지 않은 경로는 catalog 계약에 따라 full-suite로 fallback한다.

## 검증 전략

먼저 `scripts/test/ci-fan-in.test.mjs`를 추가해 현재 직렬 workflow에서 실패하는 RED 상태를 확인한다. 테스트는 lane 책임 분리, immutable Toolkit SHA, concurrency 상한, final `verify`의 모든 dependency, `always()` 실행, fail-closed 조건과 필수 output 검사를 고정한다.

구현 후 focused fixture와 전체 Node fixture를 실행하고 repository hygiene, public contract/generated artifact 검증, Gradle 전체 suite, AI pytest·lint·typecheck·offline evaluation을 수행한다. PR에서는 native lane과 Toolkit matrix가 동시에 시작되는지, required `CI / verify`가 마지막에 실행되는지, module/aggregate artifact가 다운로드 가능한지 확인한다. 실패 fixture 또는 실제 실패 run 없이 성공 경로만 확인한 경우 #364를 완료로 처리하지 않는다.

## 완료 조건과 후속 상태

#368은 valid baseline artifact, 세 immutable run URL, #364 PR 연결이 모두 확인될 때 종료한다. #364는 병렬 PR run과 final fan-in의 성공·실패 의미가 검증되고 PR이 develop에 병합된 뒤 종료한다.

#255는 #363, #368, #369, #370, #364, #365, #366을 포함하는 상위 이슈다. 이번 작업 후에도 #366의 장기 benchmark evidence와 release approval gate가 남으므로 #255는 열린 상태로 유지하고 체크리스트만 갱신한다. #234는 Swagger 범위가 완료됐지만 관측성 후속이 분리 중이므로 이번 CI 변경으로 종료하지 않는다.
