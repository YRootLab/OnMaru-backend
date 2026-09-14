package com.yrootlab.onmaru.identity.guest;

public final class GuestGrantAlreadyClaimedException extends RuntimeException {

    public GuestGrantAlreadyClaimedException() {
        super("guest exploration grant is already claimed");
    }
}
