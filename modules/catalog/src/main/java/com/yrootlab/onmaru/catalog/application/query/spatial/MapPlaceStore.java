package com.yrootlab.onmaru.catalog.application.query.spatial;

import java.util.List;

public interface MapPlaceStore {

    List<MapPlaceProjection> findPublishedSnapshot();
}
