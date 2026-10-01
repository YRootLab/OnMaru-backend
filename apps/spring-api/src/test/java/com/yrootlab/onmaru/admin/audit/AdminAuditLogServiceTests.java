package com.yrootlab.onmaru.admin.audit;

import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuditLogServiceTests {
    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private static final UUID ACTOR = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Test
    void appendsImmutableAuditRecordWithActorAndTimestamp() {
        var store = new InMemoryAdminAuditLogStore();
        var service = new AdminAuditLogService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        var before = Map.<String, Object>of("status", "OPEN");

        var created = service.append(
                new AdminPrincipal(ACTOR, "admin@onmaru.kr", AdminRole.ADMIN),
                "REVIEW_APPROVE", "REVIEW", "review-1", null, "승인 처리",
                before, Map.of("status", "APPROVED"), UUID.randomUUID());

        assertThat(created.actorAdminId()).isEqualTo(ACTOR);
        assertThat(created.createdAt()).isEqualTo(NOW);
        assertThat(store.findByActor(ACTOR, 10)).containsExactly(created);
        assertThatThrownBy(() -> created.beforeState().put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void returnsResourceHistoryNewestFirstAndRejectsMissingActor() {
        var store = new InMemoryAdminAuditLogStore();
        var actor = new AdminPrincipal(ACTOR, "admin@onmaru.kr", AdminRole.ADMIN);

        var first = new AdminAuditLogService(store, Clock.fixed(NOW, ZoneOffset.UTC))
                .append(actor, "A", "SANCTION", "member-1", null, null, null, null, null);
        var second = new AdminAuditLogService(store, Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC))
                .append(actor, "B", "SANCTION", "member-1", null, null, null, null, null);

        assertThat(store.findByResource("SANCTION", "member-1", 10)).containsExactly(second, first);
        assertThatThrownBy(() -> new AdminAuditLogService(store, Clock.fixed(NOW, ZoneOffset.UTC))
                .append(null, "A", "SANCTION", "member-1", null, null, null, null, null))
                .isInstanceOf(SecurityException.class);
    }
}
