package com.yrootlab.onmaru.tourism.insights;

public final class DataLabObservationMappingException extends RuntimeException {

    public DataLabObservationMappingException(String field) {
        super(field);
    }

    public String field() {
        return getMessage();
    }
}
