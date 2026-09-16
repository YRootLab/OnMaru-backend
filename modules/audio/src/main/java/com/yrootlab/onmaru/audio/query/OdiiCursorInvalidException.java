package com.yrootlab.onmaru.audio.query;

public final class OdiiCursorInvalidException extends RuntimeException {

    public OdiiCursorInvalidException() {
        super("Odii cursor is invalid");
    }

    public OdiiCursorInvalidException(Throwable cause) {
        super("Odii cursor is invalid", cause);
    }
}
