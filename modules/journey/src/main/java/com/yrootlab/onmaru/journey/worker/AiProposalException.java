package com.yrootlab.onmaru.journey.worker;

public final class AiProposalException extends RuntimeException {

    private final WorkerDegradedReason degradedReason;
    private final long retryAfterMs;

    public AiProposalException(WorkerDegradedReason degradedReason) {
        this(degradedReason, 0L);
    }

    public AiProposalException(WorkerDegradedReason degradedReason, long retryAfterMs) {
        super(degradedReason.name());
        this.degradedReason = degradedReason;
        this.retryAfterMs = Math.max(0L, retryAfterMs);
    }

    public WorkerDegradedReason degradedReason() {
        return degradedReason;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
