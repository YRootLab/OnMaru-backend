# Release module comparison fixtures

`record.json`은 release asset envelope의 단일 레코드 예시다. 테스트가 서로 다른 run ID와
release SHA를 넣어 baseline/candidate 입력을 만든다. 실측 증적이 아니다.

`upstream/pipeline_toolkit`의 두 파일은
[Toolkit PR #130 병합 원본](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/tree/08501bf55a373e27782c89cca348040fa1affa93/src/pipeline_toolkit)
commit `08501bf55a373e27782c89cca348040fa1affa93`에서 변경 없이 복사한 offline 테스트 원본이다.
정책 stub을 재구현하지 않으며 production workflow는 이 디렉터리를 import하지 않는다.
Toolkit 갱신 시 원본 및 checksum을 함께 갱신한다.

[Issue #129](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/issues/129)와
[PR #130](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/pull/130)은 decimal 입력의 정확한
15% 경계를 보존하고 bool/비유한 metric을 거부한다. consumer adapter에 epsilon을 두지 않는다.

| 파일 | SHA-256 |
| --- | --- |
| `compare/module_benchmark.py` | `19771fbe3fe7c0c737dd553bed62836a5f619e0e8af3e0e01a759929057d25ff` |
| `contracts/module_evidence.py` | `cdc421ab2e02a1960d84e76bba8f823ed214481a6ab7a6d3b599d5444e26f161` |

`ONMARU_TOOLKIT_SRC`를 지정하면 해당 checkout으로 테스트한다. 로컬 조사용 pinned checkout이
있으면 우선 사용하며, 일반 CI hygiene에서는 offline 원본을 사용한다.
