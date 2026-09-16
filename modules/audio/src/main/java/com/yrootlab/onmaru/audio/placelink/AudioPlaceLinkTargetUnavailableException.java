package com.yrootlab.onmaru.audio.placelink;

public final class AudioPlaceLinkTargetUnavailableException extends RuntimeException {

    public AudioPlaceLinkTargetUnavailableException() {
        super("canonical place is not publicly available");
    }
}
