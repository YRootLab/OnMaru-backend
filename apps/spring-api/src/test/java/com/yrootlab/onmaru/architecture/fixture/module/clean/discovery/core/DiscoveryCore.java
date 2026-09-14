package com.yrootlab.onmaru.architecture.fixture.module.clean.discovery.core;

import com.yrootlab.onmaru.architecture.fixture.module.clean.discovery.port.CandidateSearchPort;

class DiscoveryCore {

    private final CandidateSearchPort candidateSearchPort;

    DiscoveryCore(CandidateSearchPort candidateSearchPort) {
        this.candidateSearchPort = candidateSearchPort;
    }
}
