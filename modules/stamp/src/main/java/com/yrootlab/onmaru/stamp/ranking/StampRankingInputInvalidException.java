package com.yrootlab.onmaru.stamp.ranking;

public final class StampRankingInputInvalidException extends RuntimeException {
    public StampRankingInputInvalidException(String field) {
        super(field);
    }
}
