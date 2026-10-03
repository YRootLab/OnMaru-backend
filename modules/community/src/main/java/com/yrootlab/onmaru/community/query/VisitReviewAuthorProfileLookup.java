package com.yrootlab.onmaru.community.query;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@FunctionalInterface
public interface VisitReviewAuthorProfileLookup {

    Map<UUID, VisitReviewAuthor> findByMemberIds(Set<UUID> memberIds);
}
