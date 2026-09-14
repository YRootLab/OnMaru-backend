package com.yrootlab.onmaru.architecture.fixture.module.clean.app.bridge;

import com.yrootlab.onmaru.architecture.fixture.module.clean.catalog.api.CatalogQueryApi;
import com.yrootlab.onmaru.architecture.fixture.module.clean.community.port.PlaceEligibilityPort;

class CatalogPlaceEligibilityBridge implements PlaceEligibilityPort {

    private final CatalogQueryApi catalogQueryApi;

    CatalogPlaceEligibilityBridge(CatalogQueryApi catalogQueryApi) {
        this.catalogQueryApi = catalogQueryApi;
    }
}
