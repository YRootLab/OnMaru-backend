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

현재 서비스는 외부 모델이나 DB에 연결하지 않는다. Gemini adapter, 내부 서비스 인증, guardrail과 RAG 의존성은 각 후속 Issue가 계약과 버전을 정한 뒤 추가한다.
