package com.yrootlab.onmaru.community.region;

public final class VisitReviewRegionInvalidRequestException extends RuntimeException {

    public VisitReviewRegionInvalidRequestException(String field) {
        super(field);
    }

    public String field() {
        return getMessage();
    }
}
