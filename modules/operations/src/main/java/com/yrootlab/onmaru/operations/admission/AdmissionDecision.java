package com.yrootlab.onmaru.operations.admission;

import java.time.Duration;

public record AdmissionDecision(boolean allowed, Duration retryAfter, AdmissionRejectionReason reason) {

    public static AdmissionDecision allow() {
        return new AdmissionDecision(true, Duration.ZERO, null);
    }

    public static AdmissionDecision rejected(Duration retryAfter) {
        return rejected(retryAfter, AdmissionRejectionReason.QUOTA_EXCEEDED);
    }

    public static AdmissionDecision rejected(Duration retryAfter, AdmissionRejectionReason reason) {
        return new AdmissionDecision(false, retryAfter, reason);
    }
}
