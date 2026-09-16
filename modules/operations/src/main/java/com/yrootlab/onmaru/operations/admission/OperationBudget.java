package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;

public record OperationBudget(String operation, SubjectType subjectType, int limit, Duration window, int activeLimit) {
    public OperationBudget(String operation, SubjectType subjectType, int limit) {
        this(operation, subjectType, limit, null, 0);
    }

    public OperationBudget(String operation, SubjectType subjectType, int limit, Duration window) {
        this(operation, subjectType, limit, window, 0);
    }

    public OperationBudget {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (subjectType == null) {
            throw new IllegalArgumentException("subjectType must not be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        if (window != null && (window.isZero() || window.isNegative())) {
            throw new IllegalArgumentException("window must be positive");
        }
        if (activeLimit < 0) {
            throw new IllegalArgumentException("activeLimit must not be negative");
        }
    }
}
