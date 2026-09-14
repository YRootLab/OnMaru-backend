package com.yrootlab.onmaru.community.command.review;

import java.util.Optional;

@FunctionalInterface
public interface VisitReviewPlaceLookup {

    Optional<VisitReviewPlace> findEligiblePlace(String placeId);
}
