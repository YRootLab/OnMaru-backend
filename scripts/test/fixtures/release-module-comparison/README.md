# Release module comparison fixtures

`record.json`은 release asset envelope의 단일 레코드 예시다. 테스트가 서로 다른 run ID와
release SHA를 넣어 baseline/candidate 입력을 만든다. 실측 증적이 아니다.

`upstream/pipeline_toolkit`의 두 파일은
[Toolkit v0.1.3](https://github.com/YRootLab/OnMaru-backend-ci-toolkit/tree/59b3344ecdd4460451e4e67db973d6dfd4afa8b5/src/pipeline_toolkit)
commit `59b3344ecdd4460451e4e67db973d6dfd4afa8b5`에서 변경 없이 복사한 offline 테스트 원본이다.
정책 stub을 재구현하지 않으며 production workflow는 이 디렉터리를 import하지 않는다.
Toolkit 갱신 시 원본 및 checksum을 함께 갱신한다.

| 파일 | SHA-256 |
| --- | --- |
| `compare/module_benchmark.py` | `83c36ed56632fde8c023b3fe7c2832f098aae9fa77c0915496426799d4aae872` |
| `contracts/module_evidence.py` | `8c85b8ff8d21098cbc970b7ba68aed947e5873e9e4f98e845a0108ca11f3d2dd` |

`ONMARU_TOOLKIT_SRC`를 지정하면 해당 checkout으로 테스트한다. 로컬 조사용 pinned checkout이
있으면 우선 사용하며, 일반 CI hygiene에서는 offline 원본을 사용한다.
