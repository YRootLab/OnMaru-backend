package com.yrootlab.onmaru.operations.admission;

public record AdmissionRequest(String operation, AdmissionSubject subject) {
    public AdmissionRequest {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be blank");
        }
        if (subject == null) {
            throw new IllegalArgumentException("subject must not be null");
        }
    }
}
