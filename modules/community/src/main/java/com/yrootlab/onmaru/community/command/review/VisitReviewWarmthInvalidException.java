package com.yrootlab.onmaru.community.command.review;

public final class VisitReviewWarmthInvalidException extends RuntimeException {

    private final String field;

    public VisitReviewWarmthInvalidException(String field) {
        this.field = field;
    }

    public String field() {
        return field;
    }
}
