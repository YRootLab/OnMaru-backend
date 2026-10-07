package com.yrootlab.onmaru.community.command.review;

import com.yrootlab.onmaru.catalog.externalplace.ExternalPlaceCandidate;

import java.util.List;

public record CreateExternalPlaceVisitReviewCommand(
        ExternalPlaceCandidate place,
        String text,
        String mood,
        Integer score,
        List<String> tags) {
}
