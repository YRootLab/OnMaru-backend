# Backend 설계 입력 스냅샷

`planning-inputs/`는 backend 구현 이슈가 참조하는 FE specs와 schema guide 입력을 저장소 안에 고정한다. `docs/backend_schema_design_guide.md`와 `docs/specs`는 이 폴더를 가리키는 상대 symlink이므로, 원본 checkout이 없어도 문서를 읽고 CI에서 검증할 수 있다.

## 구성

- `planning-inputs/backend_schema_design_guide.md`: `YRootLab/OnMaru-docs`의 schema guide 스냅샷
- `planning-inputs/specs/`: `YRootLab/OnMaru-Frontend`의 `docs/specs` 스냅샷
- `planning-inputs/manifest.json`: 각 파일의 원본 repository, commit, source path, SHA-256

## 검증

```bash
node scripts/verify-planning-inputs.mjs
```

검증은 다음 경우 실패한다.

- manifest에 기록된 파일이 누락됨
- 파일 내용의 SHA-256이 manifest와 다름
- 스냅샷 폴더에 manifest 밖 파일이 추가됨
- 스냅샷 또는 contract fixture에 secret-like 값이 포함됨

## 갱신 절차

1. 원본 repository의 반영할 commit을 확정한다.
2. `docs/reference-snapshots/planning-inputs` 아래 파일을 해당 commit의 blob으로 교체한다.
3. `manifest.json`의 `source_commit`, `source_path`, `sha256`을 함께 갱신한다.
4. `node scripts/verify-planning-inputs.mjs`와 `node --test scripts/test/*.test.mjs`를 실행한다.

원본 working tree에 커밋되지 않은 변경이 있으면 그 내용은 스냅샷 기준으로 사용하지 않는다. 재현 가능한 commit blob만 기록한다.
