package com.yrootlab.onmaru.identity.guest;

public final class GuestGrantNotFoundException extends RuntimeException {

    public GuestGrantNotFoundException() {
        super("guest exploration grant was not found");
    }
}
