package com.yrootlab.onmaru.operations.admission;

public record AdmissionSubject(SubjectType type, String key) {
    public AdmissionSubject {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
    }
}
