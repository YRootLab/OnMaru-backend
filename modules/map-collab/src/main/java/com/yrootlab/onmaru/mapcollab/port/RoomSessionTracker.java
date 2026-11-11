package com.yrootlab.onmaru.mapcollab.port;

import java.util.UUID;

public interface RoomSessionTracker {
    boolean isMemberOnline(UUID roomId, UUID memberId);
}
