package com.yrootlab.onmaru.audio.placelink;

public final class AudioPlaceLinkCandidateNotFoundException extends RuntimeException {

    public AudioPlaceLinkCandidateNotFoundException() {
        super("audio place link candidate not found");
    }
}
