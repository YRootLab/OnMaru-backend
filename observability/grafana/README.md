# Grafana Cloud observability exports

이 폴더는 OnMaru MVP 운영 관측성을 Grafana Cloud에 재현 가능한 형태로 등록하기 위한 dashboard, alert rule, notification policy export를 보관한다.

Discord는 통지 경로이고 alert state의 정답은 Grafana Cloud다. resolve message는 Grafana notification policy에서 활성화하며, critical route는 Discord와 email contact point를 동시에 사용한다.

민감 정보 정책:

- label과 annotation에 cookie, token, query, exact location, userId, placeId, runId를 넣지 않는다.
- alert annotation은 environment, service, errorCode 또는 revision, dashboard URL, runbook URL만 사용한다.
- credential, webhook URL, 수신 email 값은 이 저장소에 기록하지 않는다.
