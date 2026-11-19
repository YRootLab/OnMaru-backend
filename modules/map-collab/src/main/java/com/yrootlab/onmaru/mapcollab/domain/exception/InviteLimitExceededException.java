package com.yrootlab.onmaru.mapcollab.domain.exception;

import java.util.Map;

public final class InviteLimitExceededException extends MapCollabException {
    public InviteLimitExceededException() {
        super("INVITE_LIMIT_EXCEEDED", 409, "active invite limit exceeded", Map.of("limit", 5));
    }
}
