package com.yrootlab.onmaru.web.admin;

import com.yrootlab.onmaru.admin.auth.AdminAuthenticator;
import com.yrootlab.onmaru.admin.auth.AdminJwtTokenCodec;
import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminRole;
import com.yrootlab.onmaru.admin.curation.InMemoryAdminCurationStore;
import com.yrootlab.onmaru.admin.pagination.AdminCursorCodec;
import com.yrootlab.onmaru.admin.users.AdminMember;
import com.yrootlab.onmaru.admin.users.InMemoryAdminMemberStore;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdminListTotalCountBoundaryTests {
    private static final Instant NOW = Instant.parse("2026-10-03T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private final FakeSecretProvider secrets = new FakeSecretProvider();
    private final AdminJwtTokenCodec jwt = new AdminJwtTokenCodec(
            secrets, "admin.jwt-signing-key", "onmaru-admin", "onmaru-admin-web", Duration.ofMinutes(15), CLOCK);
    private final String bearer = "Bearer " + jwt.issue(new AdminPrincipal(
            new UUID(0, 501), "admin@example.com", AdminRole.ADMIN));
    private final AdminCursorCodec cursors = new AdminCursorCodec(secrets, "admin.cursor-signing-key", CLOCK);

    @Test
    void userPagesReturnTheFilteredTotalCountOnEveryCursorPage() {
        var store = new InMemoryAdminMemberStore();
        store.add(new AdminMember(new UUID(0, 1), "ACTIVE", NOW.minusSeconds(3), 0));
        store.add(new AdminMember(new UUID(0, 2), "ACTIVE", NOW.minusSeconds(2), 0));
        store.add(new AdminMember(new UUID(0, 3), "DELETING", NOW.minusSeconds(1), 0));
        var controller = new AdminUserController(new AdminAuthenticator(jwt), store, cursors);
        var request = new MockHttpServletRequest();

        var first = body(controller.users("ACTIVE", 1, null, bearer, request));
        assertThat(first.get("totalCount")).isEqualTo(2L);
        store.add(new AdminMember(new UUID(0, 4), "ACTIVE", NOW.minusSeconds(4), 0));
        var second = body(controller.users("ACTIVE", 1, (String) first.get("nextCursor"), bearer, request));
        assertThat(second.get("totalCount")).isEqualTo(2L);
    }

    @Test
    void curationPagesReturnTheFilteredTotalCountOnEveryCursorPage() {
        var store = new InMemoryAdminCurationStore();
        var adminId = new UUID(0, 501);
        store.upsert(new UUID(1, 1), "VILLAGE", true, List.of(), adminId);
        store.upsert(new UUID(1, 2), "VILLAGE", true, List.of(), adminId);
        store.upsert(new UUID(1, 3), "VILLAGE", false, List.of(), adminId);
        var controller = new AdminCurationController(
                new AdminAuthenticator(jwt), store, null, null, cursors);
        var request = new MockHttpServletRequest();

        var first = body(controller.list("VILLAGE", true, 1, null, bearer, request));
        assertThat(first.get("totalCount")).isEqualTo(2L);
        var second = body(controller.list(
                "VILLAGE", true, 1, (String) first.get("nextCursor"), bearer, request));
        assertThat(second.get("totalCount")).isEqualTo(2L);
    }

    private Map<?, ?> body(org.springframework.http.ResponseEntity<?> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (Map<?, ?>) response.getBody();
    }
}
