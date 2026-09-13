import asyncio

from httpx import ASGITransport, AsyncClient, Response

from onmaru_ai.main import create_app


async def request(path: str) -> Response:
    transport = ASGITransport(app=create_app())
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        return await client.get(path)


def test_health_reports_process_is_alive() -> None:
    response = asyncio.run(request("/health"))

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_readiness_reports_service_can_accept_requests() -> None:
    response = asyncio.run(request("/ready"))

    assert response.status_code == 200
    assert response.json() == {"status": "ready"}
