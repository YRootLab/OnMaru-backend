package com.yrootlab.onmaru.catalog.application.qualification;

import java.util.Optional;

public record QualificationResult(
        QualificationStatus status,
        Optional<CanonicalCandidate> candidate,
        Optional<QuarantineRecord> quarantine
) {
    static QualificationResult candidate(CanonicalCandidate candidate) {
        return new QualificationResult(QualificationStatus.CANDIDATE, Optional.of(candidate), Optional.empty());
    }

    static QualificationResult quarantined(QuarantineRecord quarantine) {
        return new QualificationResult(QualificationStatus.QUARANTINED, Optional.empty(), Optional.of(quarantine));
    }
}
