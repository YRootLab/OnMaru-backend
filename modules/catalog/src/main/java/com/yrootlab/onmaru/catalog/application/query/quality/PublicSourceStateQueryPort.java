package com.yrootlab.onmaru.catalog.application.query.quality;

import java.util.Optional;

public interface PublicSourceStateQueryPort {

    Optional<PublicSourceStateItem> findPublicSourceState(String sourceId, String sourceRevision);
}
