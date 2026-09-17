package com.yrootlab.onmaru.journey.timeline;

import java.util.List;
import java.util.UUID;

public interface TimelineVisitReviewSource {

    List<TimelineVisitReviewRecord> records(UUID memberId);
}
