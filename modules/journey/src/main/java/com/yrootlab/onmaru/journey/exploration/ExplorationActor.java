package com.yrootlab.onmaru.journey.exploration;

import java.util.UUID;

public record ExplorationActor(ExplorationActorType type, String subject) {

    public ExplorationActor {
        if (type == null || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("exploration actor must be complete");
        }
    }

    public static ExplorationActor member(UUID memberId) {
        return new ExplorationActor(ExplorationActorType.MEMBER, memberId.toString());
    }

    public static ExplorationActor guest(String guestSubject) {
        return new ExplorationActor(ExplorationActorType.GUEST, guestSubject);
    }
}
