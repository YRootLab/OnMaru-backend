package com.yrootlab.onmaru.identity.profile;

public final class MemberProfileInvalidException extends RuntimeException {

    private final String field;

    public MemberProfileInvalidException(String field) {
        super("Invalid member profile field: " + field);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
