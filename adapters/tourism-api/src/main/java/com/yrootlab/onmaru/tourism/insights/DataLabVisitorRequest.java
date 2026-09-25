package com.yrootlab.onmaru.tourism.insights;

import java.time.LocalDate;
import java.util.Objects;

/** Request contract for DataLab's metco and locgo regional visitor endpoints. */
public record DataLabVisitorRequest(
        Scope scope,
        LocalDate startDate,
        LocalDate endDate,
        int numOfRows
) {
    public enum Scope {
        METROPOLITAN("metcoRegnVisitrDDList"),
        LOCAL_GOVERNMENT("locgoRegnVisitrDDList");

        private final String operation;

        Scope(String operation) {
            this.operation = operation;
        }

        String operation() {
            return operation;
        }

    }

    public DataLabVisitorRequest {
        Objects.requireNonNull(scope, "scope must not be null");
        Objects.requireNonNull(startDate, "startDate must not be null");
        Objects.requireNonNull(endDate, "endDate must not be null");
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (numOfRows < 1) {
            throw new IllegalArgumentException("numOfRows must be greater than zero");
        }
    }

    public static DataLabVisitorRequest metropolitan(
            LocalDate startDate, LocalDate endDate, int numOfRows) {
        return new DataLabVisitorRequest(Scope.METROPOLITAN, startDate, endDate, numOfRows);
    }

    public static DataLabVisitorRequest localGovernment(
            LocalDate startDate, LocalDate endDate, int numOfRows) {
        return new DataLabVisitorRequest(Scope.LOCAL_GOVERNMENT, startDate, endDate, numOfRows);
    }
}
