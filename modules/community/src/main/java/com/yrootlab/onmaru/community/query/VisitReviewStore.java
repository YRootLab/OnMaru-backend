package com.yrootlab.onmaru.community.query;

import java.util.List;

public interface VisitReviewStore {

    List<VisitReviewProjection> findSnapshot();
}
