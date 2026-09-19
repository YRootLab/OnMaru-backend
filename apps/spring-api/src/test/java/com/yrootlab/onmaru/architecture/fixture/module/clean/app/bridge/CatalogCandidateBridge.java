package com.yrootlab.onmaru.architecture.fixture.module.clean.app.bridge;

import com.yrootlab.onmaru.architecture.fixture.module.clean.catalog.api.CatalogQueryApi;
import com.yrootlab.onmaru.architecture.fixture.module.clean.discovery.port.CandidateSearchPort;

class CatalogCandidateBridge implements CandidateSearchPort {

    private final CatalogQueryApi catalogQueryApi;

    CatalogCandidateBridge(CatalogQueryApi catalogQueryApi) {
        this.catalogQueryApi = catalogQueryApi;
    }
}
