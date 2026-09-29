package com.yrootlab.onmaru.admin.users;

import com.yrootlab.onmaru.admin.auth.AdminPrincipal;
import com.yrootlab.onmaru.admin.auth.AdminRole;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminSanctionServiceTests {

    private static final Instant NOW = Instant.parse("2026-09-29T00:00:00Z");
    private final UUID memberId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private final AdminPrincipal admin = new AdminPrincipal(
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), "admin@onmaru.kr", AdminRole.ADMIN);
    private final AdminPrincipal editor = new AdminPrincipal(
            UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"), "editor@onmaru.kr", AdminRole.EDITOR);

    @Test
    void adminCanCreateAndRevokeOnlyOneActiveSanction() {
        var store = new InMemoryAdminSanctionStore();
        var service = new AdminSanctionService(store, fixedClock());
        var sanction = service.create(admin, memberId, "악성 신고 반복", NOW, NOW.plusSeconds(3600));

        assertThat(service.find(memberId)).containsExactly(sanction);
        assertThatThrownBy(() -> service.create(admin, memberId, "두 번째 제재", NOW, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(service.revoke(admin, sanction.id())).isTrue();
        assertThat(service.find(memberId).getFirst().status()).isEqualTo("REVOKED");
    }

    @Test
    void editorCannotMutateSanctions() {
        var service = new AdminSanctionService(new InMemoryAdminSanctionStore(), fixedClock());

        assertThatThrownBy(() -> service.create(editor, memberId, "사유", NOW, null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rejectsInvalidPeriod() {
        var service = new AdminSanctionService(new InMemoryAdminSanctionStore(), fixedClock());

        assertThatThrownBy(() -> service.create(admin, memberId, "사유", NOW, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Clock fixedClock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }
}
