package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class PinNotFoundException extends MapCollabException {
    public PinNotFoundException() {
        super("PIN_NOT_FOUND", 404, "pin not found");
    }
}
