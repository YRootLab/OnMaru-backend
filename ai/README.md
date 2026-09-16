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

서버 실행 후 다음 응답을 확인한다.

```bash
curl http://localhost:8001/health
# {"status":"ok"}

curl http://localhost:8001/ready
# {"status":"ready"}
```

## 현재 범위

Gemini adapter는 opt-in composition에서만 외부 모델에 연결한다. 기본 health runtime은 외부 호출을 만들지 않으며, 설정과 제한된 smoke 절차는 [`docs/ai/gemini-provider-adapter.md`](../docs/ai/gemini-provider-adapter.md)를 따른다. DB와 RAG 연결은 각 후속 Issue가 계약과 버전을 정한 뒤 추가한다.
