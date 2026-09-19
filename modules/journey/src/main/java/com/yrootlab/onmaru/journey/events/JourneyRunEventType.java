package com.yrootlab.onmaru.journey.events;

public enum JourneyRunEventType {
    STAGE("run.stage"),
    TERMINAL("run.terminal"),
    HEARTBEAT("heartbeat"),
    RESET("reset"),
    AUTH_CLOSED("auth_closed");

    private final String wireName;

    JourneyRunEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
