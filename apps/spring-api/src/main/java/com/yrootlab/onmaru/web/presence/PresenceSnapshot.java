package com.yrootlab.onmaru.web.presence;

record PresenceSnapshot(String roomId, int activeCount, int todayVisitors, long serverTime) {}
