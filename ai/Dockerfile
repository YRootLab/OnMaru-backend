# syntax=docker/dockerfile:1.7

FROM ghcr.io/astral-sh/uv:python3.12-alpine AS builder
WORKDIR /workspace/ai

COPY ai/pyproject.toml ai/uv.lock ./
COPY ai/src src

RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12-alpine AS runner
WORKDIR /app

RUN addgroup -S onmaru \
    && adduser -S onmaru -G onmaru

COPY --from=builder --chown=onmaru:onmaru /workspace/ai/.venv /app/.venv

ENV PATH="/app/.venv/bin:${PATH}" \
    PYTHONUNBUFFERED=1 \
    ONMARU_AI_HOST=0.0.0.0 \
    ONMARU_AI_PORT=8000

USER onmaru
EXPOSE 8000
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/ready', timeout=2).read()" || exit 1

CMD ["python", "-m", "uvicorn", "onmaru_ai.main:app", "--host", "0.0.0.0", "--port", "8000"]
