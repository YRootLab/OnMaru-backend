package com.yrootlab.onmaru.identity.lifecycle;

public final class MemberAccessDeniedException extends RuntimeException {

    public MemberAccessDeniedException() {
        super("member access is currently restricted");
    }
}
