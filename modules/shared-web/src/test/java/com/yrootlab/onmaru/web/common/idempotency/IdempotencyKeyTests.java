package com.yrootlab.onmaru.web.common.idempotency;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyTests {

    @Test
    void parsesUuidHeaderAfterTrimmingWhitespace() {
        var key = IdempotencyKey.fromHeader(" 00000000-0000-0000-0000-000000000001 ");

        assertThat(key.value()).isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    @Test
    void rejectsMissingHeaderBeforeCommandExecution() {
        assertThatThrownBy(() -> IdempotencyKey.fromHeader(" "))
                .isInstanceOf(IdempotencyKeyMissingException.class);
    }

    @Test
    void rejectsMalformedHeaderBeforeCommandExecution() {
        assertThatThrownBy(() -> IdempotencyKey.fromHeader("not-a-uuid"))
                .isInstanceOf(IdempotencyKeyInvalidException.class);
    }
}
