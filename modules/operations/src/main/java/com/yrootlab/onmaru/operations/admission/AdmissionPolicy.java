package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;
import java.util.List;

public record AdmissionPolicy(Duration window, List<OperationBudget> budgets) {
    public AdmissionPolicy {
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive");
        }
        budgets = budgets == null ? List.of() : List.copyOf(budgets);
    }

    OperationBudget budgetFor(String operation, SubjectType subjectType) {
        return budgets.stream()
                .filter(budget -> budget.operation().equals(operation))
                .filter(budget -> budget.subjectType() == subjectType)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No admission budget for operation " + operation));
    }
}
