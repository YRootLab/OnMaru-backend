package com.yrootlab.onmaru.journey.worker;

public final class AiProposalException extends RuntimeException {

    private final WorkerDegradedReason degradedReason;

    public AiProposalException(WorkerDegradedReason degradedReason) {
        super(degradedReason.name());
        this.degradedReason = degradedReason;
    }

    public WorkerDegradedReason degradedReason() {
        return degradedReason;
    }
}
