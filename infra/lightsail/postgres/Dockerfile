FROM postgres:17-bookworm

ARG PG_SEARCH_VERSION=0.27.0

RUN apt-get update && apt-get install -y --no-install-recommends \
      curl ca-certificates postgresql-17-postgis-3 postgresql-17-pgvector \
    && curl -fsSL -o /tmp/pg_search.deb \
      "https://github.com/paradedb/paradedb/releases/download/v${PG_SEARCH_VERSION}/postgresql-17-pg-search_${PG_SEARCH_VERSION}-1PARADEDB-bookworm_amd64.deb" \
    && apt-get install -y --no-install-recommends /tmp/pg_search.deb \
    && rm -f /tmp/pg_search.deb \
    && rm -rf /var/lib/apt/lists/*
