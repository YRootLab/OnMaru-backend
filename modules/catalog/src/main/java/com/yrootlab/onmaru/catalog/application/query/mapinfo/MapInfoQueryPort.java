package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.List;

/** Read-only port for the published map projection. Implementations must page in SQL. */
public interface MapInfoQueryPort {
    MapInfoQueryResult find(MapInfoSqlQuery query);
}
