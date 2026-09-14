package com.yrootlab.onmaru.operations.admission;

public record OperationBudget(String operation, SubjectType subjectType, int limit) {
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
    }
}
