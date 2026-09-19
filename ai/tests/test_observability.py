import asyncio

from fastapi import FastAPI
from httpx import ASGITransport, AsyncClient, Response
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from onmaru_ai.main import create_app
from onmaru_ai.observability import (
    InMemoryTelemetrySink,
    OpenTelemetryTelemetrySink,
    TelemetryEvent,
    create_telemetry_sink,
)


async def request(app: FastAPI, path: str, headers: dict[str, str]) -> Response:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get(path, headers=headers)


def test_correlates_request_run_revision_and_trace_without_sensitive_attributes() -> None:
    sink = InMemoryTelemetrySink()
    app = create_app(telemetry_sink=sink)

    response = asyncio.run(
        request(
            app,
            "/ready?query=secret&location=secret",
            headers={
                "X-Request-Id": "req-123",
                "X-Run-Id": "run-456",
                "X-Revision": "rev-789",
                "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
                "Cookie": "session=secret",
                "Authorization": "Bearer secret-token",
            },
        )
    )

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "req-123"
    assert response.headers["X-Run-Id"] == "run-456"
    assert response.headers["X-Revision"] == "rev-789"

    event = sink.events[0]
    assert event.name == "http.server.request"
    assert event.attributes == {
        "request.id": "req-123",
        "trace.id": "4bf92f3577b34da6a3ce929d0e0e4736",
        "run.id": "run-456",
        "revision": "rev-789",
        "http.request.method": "GET",
        "http.route": "/ready",
        "http.response.status_code": "200",
    }
    assert "query" not in event.attributes
    assert "location" not in event.attributes
    assert "cookie" not in event.attributes
    assert "token" not in event.attributes
    assert "evidence.body" not in event.attributes
    assert "secret" not in event.attributes.values()
    assert "secret-token" not in event.attributes.values()
    assert "session=secret" not in event.attributes.values()


def test_open_telemetry_sink_emits_sanitized_span_attributes() -> None:
    exporter = InMemorySpanExporter()
    provider = TracerProvider()
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    sink = OpenTelemetryTelemetrySink(provider.get_tracer("test-onmaru-ai"))

    sink.record(
        TelemetryEvent(
            name="http.server.request",
            attributes={
                "request.id": "req-123",
                "trace.id": "4bf92f3577b34da6a3ce929d0e0e4736",
                "run.id": "run-456",
                "revision": "rev-789",
                "http.request.method": "GET",
                "http.route": "/ready",
                "http.response.status_code": "200",
            },
        )
    )

    spans = exporter.get_finished_spans()
    assert len(spans) == 1
    assert spans[0].name == "http.server.request"
    assert spans[0].attributes == {
        "request.id": "req-123",
        "trace.id": "4bf92f3577b34da6a3ce929d0e0e4736",
        "run.id": "run-456",
        "revision": "rev-789",
        "http.request.method": "GET",
        "http.route": "/ready",
        "http.response.status_code": "200",
    }


def test_create_telemetry_sink_accepts_otlp_environment_without_exporting_secrets() -> None:
    sink = create_telemetry_sink(
        {
            "OTEL_EXPORTER_OTLP_ENDPOINT": "https://otlp.example.test",
            "OTEL_EXPORTER_OTLP_HEADERS": "Authorization=Basic secret-token",
        }
    )

    assert isinstance(sink, OpenTelemetryTelemetrySink)
