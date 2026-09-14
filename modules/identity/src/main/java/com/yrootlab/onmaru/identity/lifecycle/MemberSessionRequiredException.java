package com.yrootlab.onmaru.identity.lifecycle;

public final class MemberSessionRequiredException extends RuntimeException {

    public MemberSessionRequiredException() {
        super("Authentication is required.");
    }
}
