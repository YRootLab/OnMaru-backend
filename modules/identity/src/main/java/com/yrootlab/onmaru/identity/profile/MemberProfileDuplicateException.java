package com.yrootlab.onmaru.identity.profile;

public final class MemberProfileDuplicateException extends RuntimeException {

    public MemberProfileDuplicateException() {
        super("Display name is already in use");
    }
}
