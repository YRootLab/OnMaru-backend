package com.yrootlab.onmaru.web.presence;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties("onmaru.presence")
record PresenceProperties(List<String> allowedRooms, int maxConnections) {

    boolean isAllowed(String roomId) {
        return roomId != null && allowedRooms != null && allowedRooms.contains(roomId);
    }
}
