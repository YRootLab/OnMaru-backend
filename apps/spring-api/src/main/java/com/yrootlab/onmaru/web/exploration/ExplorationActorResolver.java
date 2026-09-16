package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.identity.lifecycle.MemberLifecycleService;
import com.yrootlab.onmaru.identity.guest.GuestCredentialService;
import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
final class ExplorationActorResolver {

    static final String GUEST_COOKIE = "__Host-onmaru-guest";

    private final MemberLifecycleService memberLifecycleService;
    private final GuestCredentialService guestCredentialService;

    ExplorationActorResolver(
            MemberLifecycleService memberLifecycleService,
            GuestCredentialService guestCredentialService) {
        this.memberLifecycleService = memberLifecycleService;
        this.guestCredentialService = guestCredentialService;
    }

    ResolvedExplorationActor resolve(String sessionToken, String guestToken) {
        var member = memberLifecycleService.currentMember(sessionToken);
        if (member.isPresent()) {
            return new ResolvedExplorationActor(ExplorationActor.member(member.get().id()), null);
        }
        var guestId = guestCredentialService.resolve(guestToken)
                .orElseThrow(ExplorationAuthenticationRequiredException::new);
        return new ResolvedExplorationActor(ExplorationActor.guest(guestId.toString()), guestId);
    }

    void linkExploration(ResolvedExplorationActor resolved, UUID explorationId) {
        if (resolved.guestId() != null) {
            guestCredentialService.linkExploration(resolved.guestId(), explorationId);
        }
    }

    record ResolvedExplorationActor(ExplorationActor actor, UUID guestId) {
    }
}
