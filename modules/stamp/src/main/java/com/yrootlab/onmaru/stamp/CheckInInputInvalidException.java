package com.yrootlab.onmaru.stamp;

public final class CheckInInputInvalidException extends RuntimeException {
    public CheckInInputInvalidException(String field) {
        super(field);
    }
}
