package com.yrootlab.onmaru.community.command.review;

public final class VisitReviewWarmthInvalidException extends RuntimeException {

    private final String field;
    private final String reason;
    private final Integer index;

    public VisitReviewWarmthInvalidException(String field) {
        this(field, "INVALID_VALUE", null);
    }

    public VisitReviewWarmthInvalidException(String field, String reason, Integer index) {
        super(reason);
        this.field = field;
        this.reason = reason;
        this.index = index;
    }

    public String field() {
        return field;
    }

    public String reason() {
        return reason;
    }

    public Integer index() {
        return index;
    }
}
