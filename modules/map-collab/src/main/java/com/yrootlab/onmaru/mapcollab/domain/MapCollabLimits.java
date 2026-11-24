package com.yrootlab.onmaru.mapcollab.domain;

import java.time.Duration;

public final class MapCollabLimits {

    private MapCollabLimits() {}

    public static final int MAX_MEMBERS_PER_ROOM = 20;
    public static final int MAX_ACTIVE_PINS_PER_ROOM = 100;
    public static final int MAX_ACTIVE_INVITES_PER_ROOM = 5;
    public static final int MAX_CONCURRENT_WS_PER_MEMBER = 3;
    public static final Duration ROOM_LIFETIME = Duration.ofHours(72);
    public static final Duration INVITE_LIFETIME = Duration.ofHours(72);
    public static final int OUTBOX_CAPACITY = 256;
}
