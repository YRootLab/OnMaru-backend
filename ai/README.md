# FastAPI AI 서비스 개발

## VS Code

1. VS Code에서 저장소 루트 또는 `ai/`를 연다.
2. 터미널에서 `cd ai && uv sync --frozen --all-groups`를 한 번 실행한다.
3. `Python: Select Interpreter`에서 macOS/Linux는 `ai/.venv/bin/python`, Windows는 `ai/.venv/Scripts/python.exe`를 선택한다.
4. `ai/` 디렉터리에서 아래 Uvicorn 명령을 실행한다.

권장 확장은 Python과 Ruff다. 확장이 없어도 모든 검증은 터미널에서 동일하게 동작한다.

```bash
uv run uvicorn onmaru_ai.main:app --reload --port 8001
uv run ruff check .
uv run mypy src tests
uv run pytest
```

## Offline AI 평가

저장소 루트에서 고정 held-out fixture의 품질·근거·안전·지연·비용 gate를 실행한다.
외부 모델이나 네트워크를 호출하지 않는다.
launcher는 프로젝트 지원 범위 밖의 Python(`3.11` 이하 또는 `3.13` 이상)에서 실행되면
`uv`가 선택한 Python 3.12 환경으로 다시 실행한다.

```bash
uv run --project ai python scripts/run-ai-evals \
  --input ai/evals/journey-held-out-v1.json \
  --output /tmp/onmaru-ai-eval-report.json
```

이전 report와 같은 immutable gold인지 확인하고 지표 delta를 포함하려면 `--compare`를 사용한다.
dataset 표시 이름은 비교 fingerprint에 포함되지 않는다.

```bash
uv run --project ai python scripts/run-ai-evals \
  --input ai/evals/journey-held-out-v1.json \
  --output /tmp/onmaru-ai-eval-compared.json \
  --compare /tmp/onmaru-ai-eval-report.json
```

하나 이상의 gate가 실패하면 report를 남기고 종료 코드 `1`, 입력 계약이 유효하지 않으면 report
없이 종료 코드 `2`를 반환한다. fixture와 report 계약의 관리 규칙은
[`docs/ai/evaluation-harness.md`](../docs/ai/evaluation-harness.md)를 따른다.

서버 실행 후 다음 응답을 확인한다.

```bash
curl http://localhost:8001/health
# {"status":"ok"}

curl http://localhost:8001/ready
# {"status":"ready"}
```

## 현재 범위

Gemini adapter는 opt-in composition에서만 외부 모델에 연결한다. 기본 health runtime과 offline
평가는 외부 호출을 만들지 않으며, 설정과 제한된 smoke 절차는
[`docs/ai/gemini-provider-adapter.md`](../docs/ai/gemini-provider-adapter.md)를 따른다. provider
proposal은 [`docs/ai/proposal-validation.md`](../docs/ai/proposal-validation.md)의 closed schema와
candidate/evidence allowlist를 통과해야 한다. DB와 RAG 연결은 각 후속 Issue가 계약과 버전을 정한
뒤 추가한다.
