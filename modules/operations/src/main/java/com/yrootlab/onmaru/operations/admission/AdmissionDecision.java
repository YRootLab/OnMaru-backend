package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;

public record AdmissionDecision(boolean allowed, Duration retryAfter) {

    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, Duration.ZERO);
    }

    public static AdmissionDecision rejected(Duration retryAfter) {
        return new AdmissionDecision(false, retryAfter);
    }
}
