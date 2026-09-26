package com.yrootlab.onmaru.tourism.insights;

public class DataLabClientException extends RuntimeException {

    private final DataLabClientFailureKind kind;
    private final boolean retryable;

    DataLabClientException(DataLabClientFailureKind kind, String message, boolean retryable) {
        super(message);
        this.kind = kind;
        this.retryable = retryable;
    }

    DataLabClientException(DataLabClientFailureKind kind, String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryable = retryable;
    }

    public DataLabClientFailureKind kind() {
        return kind;
    }

    public boolean retryable() {
        return retryable;
    }
}
