package com.yrootlab.onmaru.mapcollab.domain.exception;

public final class InviteNotFoundException extends MapCollabException {
    public InviteNotFoundException() {
        super("INVITE_NOT_FOUND", 404, "invite not found");
    }
}
