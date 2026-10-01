package com.yrootlab.onmaru.admin.pagination;

import org.junit.jupiter.api.Test;
import com.yrootlab.onmaru.catalog.application.pagination.AdminCursor;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretBundle;

import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import java.time.Clock;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminCursorCodecTests {
    private final AdminCursorCodec codec = new AdminCursorCodec(new FakeSecretProvider(), "admin.cursor-signing-key",
            Clock.fixed(Instant.parse("2026-09-30T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void bindsSignedCursorToResourceLimitAndFilters() {
        var cursor = new AdminCursor("users", 20, "status=ACTIVE", Instant.parse("2026-09-30T00:00:00Z"), UUID.randomUUID());
        String encoded = codec.encode(cursor);

        assertThat(codec.decode(encoded, "users", 20, "status=ACTIVE")).isEqualTo(cursor);
        assertThatThrownBy(() -> codec.decode(encoded, "curations", 20, "status=ACTIVE"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(encoded, "users", 20, "status=BLOCKED"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsTamperedCursorAndWeakKey() {
        var cursor = new AdminCursor("users", 20, "", Instant.parse("2026-09-30T00:00:00Z"), UUID.randomUUID());
        String encoded = codec.encode(cursor);
        String tampered = encoded.substring(0, encoded.length() - 1) + (encoded.endsWith("a") ? "b" : "a");

        assertThatThrownBy(() -> codec.decode(tampered, "users", 20, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void optionalCursorAllowsOmissionButRejectsAnEmptyValue() {
        assertThat(codec.decodeOptional(null, "users", 20, "status=")).isNull();
        assertThatThrownBy(() -> codec.decodeOptional("", "users", 20, "status="))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decodeOptional("  ", "users", 20, "status="))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWeakSigningKeyDuringConstruction() {
        assertThatThrownBy(() -> new AdminCursorCodec(
                name -> new SecretBundle(name, "short", Optional.empty()),
                "admin.cursor-signing-key", Clock.systemUTC()))
                .isInstanceOf(IllegalStateException.class);
    }
}
