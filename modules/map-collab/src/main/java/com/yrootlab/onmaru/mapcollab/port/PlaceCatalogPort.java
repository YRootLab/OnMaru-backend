package com.yrootlab.onmaru.mapcollab.port;

import java.util.Optional;

public interface PlaceCatalogPort {
    Optional<CatalogPlaceInfo> findPlaceByPublicId(String publicId);
}
