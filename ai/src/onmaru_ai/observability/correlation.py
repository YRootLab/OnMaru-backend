from __future__ import annotations

import contextvars
import logging
import os
import re
import secrets
import uuid
from collections.abc import Awaitable, Callable, Mapping
from dataclasses import dataclass
from typing import Protocol

from fastapi import FastAPI, Request, Response
from opentelemetry import trace
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Tracer

TRACEPARENT_PATTERN = re.compile(r"^[\da-f]{2}-([\da-f]{32})-[\da-f]{16}-[\da-f]{2}$")

correlation_context: contextvars.ContextVar[dict[str, str] | None] = contextvars.ContextVar(
    "correlation_context",
    default=None,
)
logger = logging.getLogger("onmaru_ai.observability")


@dataclass(frozen=True)
class TelemetryEvent:
    name: str
    attributes: dict[str, str]


class TelemetrySink(Protocol):
    def record(self, event: TelemetryEvent) -> None:
        pass


class InMemoryTelemetrySink:
    def __init__(self) -> None:
        self.events: list[TelemetryEvent] = []

    def record(self, event: TelemetryEvent) -> None:
        self.events.append(event)


class OpenTelemetryTelemetrySink:
    def __init__(self, tracer: Tracer | None = None) -> None:
        self._tracer = tracer or trace.get_tracer("onmaru-ai")

    def record(self, event: TelemetryEvent) -> None:
        with self._tracer.start_as_current_span(event.name) as span:
            for name, value in event.attributes.items():
                span.set_attribute(name, value)


def create_telemetry_sink(environ: Mapping[str, str] | None = None) -> OpenTelemetryTelemetrySink:
    environ = environ or os.environ
    endpoint = environ.get("OTEL_EXPORTER_OTLP_TRACES_ENDPOINT") or environ.get(
        "OTEL_EXPORTER_OTLP_ENDPOINT"
    )
    if endpoint is None or not endpoint.strip():
        return OpenTelemetryTelemetrySink()

    headers = parse_otlp_headers(environ.get("OTEL_EXPORTER_OTLP_HEADERS", ""))
    resource = Resource.create(
        {
            "service.name": environ.get("OTEL_SERVICE_NAME", "onmaru-ai"),
        }
    )
    provider = TracerProvider(resource=resource)
    provider.add_span_processor(
        BatchSpanProcessor(
            OTLPSpanExporter(
                endpoint=endpoint,
                headers=headers,
            )
        )
    )
    return OpenTelemetryTelemetrySink(provider.get_tracer("onmaru-ai"))


def parse_otlp_headers(raw_headers: str) -> dict[str, str]:
    headers: dict[str, str] = {}
    for pair in raw_headers.split(","):
        if not pair or "=" not in pair:
            continue
        name, value = pair.split("=", 1)
        if name.strip():
            headers[name.strip()] = value.strip()
    return headers


@dataclass(frozen=True)
class CorrelationContext:
    request_id: str
    trace_id: str
    run_id: str
    revision: str


def install_observability(app: FastAPI, telemetry_sink: TelemetrySink) -> None:
    @app.middleware("http")
    async def correlation_middleware(
        request: Request,
        call_next: Callable[[Request], Awaitable[Response]],
    ) -> Response:
        context = correlation_from(request)
        token = correlation_context.set(
            {
                "requestId": context.request_id,
                "traceId": context.trace_id,
                "runId": context.run_id,
                "revision": context.revision,
            }
        )
        try:
            response = await call_next(request)
            response.headers["X-Request-Id"] = context.request_id
            response.headers["X-Run-Id"] = context.run_id
            response.headers["X-Revision"] = context.revision
            telemetry_sink.record(
                TelemetryEvent(
                    name="http.server.request",
                    attributes=attributes(request, response, context),
                )
            )
            logger.info(
                "http.server.request",
                extra={"onmaru": attributes(request, response, context)},
            )
            return response
        finally:
            correlation_context.reset(token)


def correlation_from(request: Request) -> CorrelationContext:
    traceparent = request.headers.get("traceparent", "")
    return CorrelationContext(
        request_id=non_blank_header(request, "x-request-id") or str(uuid.uuid4()),
        trace_id=trace_id_from_traceparent(traceparent) or secrets.token_hex(16),
        run_id=non_blank_header(request, "x-run-id") or "unknown",
        revision=non_blank_header(request, "x-revision") or "unknown",
    )


def non_blank_header(request: Request, name: str) -> str | None:
    value = request.headers.get(name)
    if value is None or not value.strip():
        return None
    return value


def trace_id_from_traceparent(traceparent: str) -> str | None:
    match = TRACEPARENT_PATTERN.match(traceparent)
    if match is None:
        return None
    trace_id = match.group(1)
    if trace_id == "00000000000000000000000000000000":
        return None
    return trace_id


def attributes(request: Request, response: Response, context: CorrelationContext) -> dict[str, str]:
    return {
        "request.id": context.request_id,
        "trace.id": context.trace_id,
        "run.id": context.run_id,
        "revision": context.revision,
        "http.request.method": request.method,
        "http.route": route_path(request),
        "http.response.status_code": str(response.status_code),
    }


def route_path(request: Request) -> str:
    route = request.scope.get("route")
    path = getattr(route, "path", None)
    if isinstance(path, str) and path:
        return path
    return "unmatched"
