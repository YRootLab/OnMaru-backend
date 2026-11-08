package com.yrootlab.onmaru.integration.ai.grant;

public final class QueryGrantException extends RuntimeException {
    public enum Code {
        SERVICE_AUTH_INVALID(401), GRANT_INVALID(401), GRANT_SCOPE_REJECTED(403),
        GRANT_CONSUMED(409), RUN_INACTIVE(409), GRANT_ALREADY_ISSUED(409), GRANT_EXPIRED(410);
        private final int status;
        Code(int status) { this.status=status; }
        public int status() { return status; }
    }
    private final Code code;
    public QueryGrantException(Code code) { super(code.name()); this.code=code; }
    public Code code() { return code; }
}
