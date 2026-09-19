package com.yrootlab.onmaru.community.query;

public final class VisitReviewInvalidRequestException extends RuntimeException {

    private final String field;

    public VisitReviewInvalidRequestException(String field) {
        super("Invalid visit review query field: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
