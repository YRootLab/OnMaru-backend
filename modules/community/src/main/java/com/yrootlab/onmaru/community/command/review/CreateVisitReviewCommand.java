package com.yrootlab.onmaru.community.command.review;

import java.util.List;

public record CreateVisitReviewCommand(String text, String mood, Integer score, List<String> tags) {

    public CreateVisitReviewCommand(String text) {
        this(text, null, null, List.of());
    }
}
