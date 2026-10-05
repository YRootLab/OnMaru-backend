package com.yrootlab.onmaru.identity.lifecycle;

import java.util.UUID;

public record MemberSummary(
        UUID id,
        String displayName,
        String characterId,
        String backgroundId) {
}
