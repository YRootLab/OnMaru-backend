package com.yrootlab.onmaru.audio.query;

public final class OdiiCursorExpiredException extends RuntimeException {

    public OdiiCursorExpiredException() {
        super("Odii cursor revision is no longer active");
    }
}
