package com.yrootlab.onmaru.journey.events;

public enum JourneyRunEventType {
    STAGE("run.stage"),
    ITEM_STARTED("run.item.started"),
    ITEM_COMPLETED("run.item.completed"),
    TEXT_DELTA("run.text.delta"),
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
