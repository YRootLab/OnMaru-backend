package com.yrootlab.onmaru.catalog.application.query.hanok;

import java.util.List;

public interface HanokListStore {

    List<HanokListProjection> findPublishedSnapshot();
}
