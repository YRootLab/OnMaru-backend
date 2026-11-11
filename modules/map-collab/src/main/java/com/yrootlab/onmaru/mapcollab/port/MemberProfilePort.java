package com.yrootlab.onmaru.mapcollab.port;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface MemberProfilePort {
    Map<UUID, String> findDisplayNames(Set<UUID> memberIds);

    default String findDisplayName(UUID memberId) {
        if (memberId == null) {
            return "";
        }
        return findDisplayNames(Set.of(memberId)).getOrDefault(memberId, "사용자");
    }
}
