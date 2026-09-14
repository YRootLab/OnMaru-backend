package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.Optional;
import java.util.UUID;

@FunctionalInterface
public interface HanokSavedStateLookup {

    boolean savedBy(Optional<UUID> memberId, String placeId);
}
