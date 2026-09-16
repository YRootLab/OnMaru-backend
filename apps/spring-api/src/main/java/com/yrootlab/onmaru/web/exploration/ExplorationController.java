package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.CreateExplorationCommand;
import com.yrootlab.onmaru.journey.exploration.CreateExplorationTurnCommand;
import com.yrootlab.onmaru.journey.exploration.ExplorationInputInvalidException;
import com.yrootlab.onmaru.journey.exploration.ExplorationService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyKey;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyCommand;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyFingerprint;
import com.yrootlab.onmaru.web.common.idempotency.IdempotencyService;
import com.yrootlab.onmaru.web.common.idempotency.IdempotentResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public final class ExplorationController {

    private static final String SESSION_COOKIE = "__Host-onmaru-session";

    private final ExplorationService explorationService;
    private final ExplorationActorResolver actorResolver;
    private final IdempotencyService idempotencyService;

    ExplorationController(
            ExplorationService explorationService,
            ExplorationActorResolver actorResolver,
            IdempotencyService idempotencyService) {
        this.explorationService = explorationService;
        this.actorResolver = actorResolver;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/api/v1/explorations")
    ResponseEntity<RunAcceptedResponse> create(
            @RequestBody(required = false) CreateRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var key = IdempotencyKey.fromHeader(idempotencyKey);
        var resolved = actorResolver.resolve(sessionToken, guestToken);
        var command = body == null
                ? new CreateExplorationCommand(null, null, null)
                : new CreateExplorationCommand(body.query(), body.locale(), body.regionCode());
        var response = idempotencyService.execute(new IdempotencyCommand(
                key.value(),
                resolved.actor().type() + ":" + resolved.actor().subject(),
                "POST",
                "/api/v1/explorations",
                IdempotencyFingerprint.sha256("POST", "/api/v1/explorations", "exploration.create", command)), () -> {
            var snapshot = explorationService.create(resolved.actor(), command);
            actorResolver.linkExploration(resolved, snapshot.explorationId());
            return IdempotentResponse.accepted(RunAcceptedResponse.from(snapshot));
        });
        return accepted(response);
    }

    @GetMapping("/api/v1/explorations/{explorationId}")
    ResponseEntity<ExplorationResponse> get(
            @PathVariable UUID explorationId,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(ExplorationResponse.from(explorationService.get(actor, explorationId)));
    }

    @PostMapping("/api/v1/explorations/{explorationId}/turns")
    ResponseEntity<RunAcceptedResponse> createTurn(
            @PathVariable UUID explorationId,
            @RequestBody(required = false) CreateTurnRequest body,
            @RequestHeader(name = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @CookieValue(name = SESSION_COOKIE, required = false) String sessionToken,
            @CookieValue(name = ExplorationActorResolver.GUEST_COOKIE, required = false) String guestToken) {
        var key = IdempotencyKey.fromHeader(idempotencyKey);
        var actor = actorResolver.resolve(sessionToken, guestToken).actor();
        if (body == null || body.baseVersion() == null) {
            throw new ExplorationInputInvalidException(body == null ? "body" : "baseVersion");
        }
        var command = new CreateExplorationTurnCommand(
                body.clientTurnId(),
                body.baseVersion(),
                body.query(),
                regionCode(body.clarificationAnswer()));
        var path = "/api/v1/explorations/" + explorationId + "/turns";
        var response = idempotencyService.execute(new IdempotencyCommand(
                key.value(),
                actor.type() + ":" + actor.subject(),
                "POST",
                path,
                IdempotencyFingerprint.sha256("POST", path, "exploration.turn", body)), () -> {
            var snapshot = explorationService.createTurn(actor, explorationId, command);
            return IdempotentResponse.accepted(RunAcceptedResponse.from(snapshot));
        });
        return accepted(response);
    }

    private String regionCode(ClarificationAnswer answer) {
        if (answer == null) {
            return null;
        }
        if (!"region".equals(answer.clarificationId())) {
            throw new ExplorationInputInvalidException("clarificationAnswer.clarificationId");
        }
        var hasChoice = answer.choiceId() != null && !answer.choiceId().isBlank();
        var hasText = answer.text() != null && !answer.text().isBlank();
        if (hasChoice == hasText || hasChoice) {
            throw new ExplorationInputInvalidException("clarificationAnswer");
        }
        var text = answer.text().trim();
        if (text.contains("전주")) {
            return "kr-45-jeonju";
        }
        if (text.contains("서울")) {
            return "kr-11-seoul";
        }
        if (text.contains("경주")) {
            return "kr-47-gyeongju";
        }
        if (text.contains("안동")) {
            return "kr-47-andong";
        }
        if (text.contains("부산")) {
            return "kr-26-busan";
        }
        return null;
    }

    private ResponseEntity<RunAcceptedResponse> accepted(IdempotentResponse response) {
        return ResponseEntity.status(response.status())
                .cacheControl(CacheControl.noStore())
                .body((RunAcceptedResponse) response.body());
    }

    record CreateRequest(String query, String locale, String regionCode) {
    }

    record CreateTurnRequest(
            UUID clientTurnId,
            Integer baseVersion,
            String query,
            ClarificationAnswer clarificationAnswer) {
    }

    record ClarificationAnswer(String clarificationId, String choiceId, String text) {
    }
}
