package com.yrootlab.onmaru.audio.sync;

public record OdiiCurationDecision(Status status, String reason) {

    public enum Status {
        INCLUDED,
        EXCLUDED,
        DUPLICATE
    }

    public OdiiCurationDecision {
        if (status == null || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("status and reason are required");
        }
    }

    public static OdiiCurationDecision included() {
        return new OdiiCurationDecision(Status.INCLUDED, "CURATED_TRADITIONAL_CULTURE");
    }

    public static OdiiCurationDecision excluded(String reason) {
        return new OdiiCurationDecision(Status.EXCLUDED, reason);
    }
}
