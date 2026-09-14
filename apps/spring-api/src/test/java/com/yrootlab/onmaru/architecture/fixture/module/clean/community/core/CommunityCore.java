package com.yrootlab.onmaru.architecture.fixture.module.clean.community.core;

import com.yrootlab.onmaru.architecture.fixture.module.clean.community.port.PlaceEligibilityPort;

class CommunityCore {

    private final PlaceEligibilityPort placeEligibilityPort;

    CommunityCore(PlaceEligibilityPort placeEligibilityPort) {
        this.placeEligibilityPort = placeEligibilityPort;
    }
}
