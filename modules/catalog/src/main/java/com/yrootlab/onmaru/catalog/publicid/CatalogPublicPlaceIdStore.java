package com.yrootlab.onmaru.catalog.publicid;

import java.util.Optional;
import java.util.UUID;

/** Catalog-owned mapping between an FE-visible place ID and the canonical place identity. */
public interface CatalogPublicPlaceIdStore {

    Optional<UUID> findPlaceId(String publicPlaceId);

    /**
     * Registers the immutable mapping. Re-registering the exact same pair is safe;
     * remapping either side to a different value is rejected.
     */
    void register(String publicPlaceId, UUID placeId);
}
