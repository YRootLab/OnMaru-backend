package com.yrootlab.onmaru.identity.guest;

import java.util.UUID;

public record MemberExplorationAccess(UUID memberId, UUID explorationId) {
}
