package com.yrootlab.onmaru.web.kcontents;

import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.web.common.cursor.CursorCodec;
import com.yrootlab.onmaru.web.common.cursor.CursorInvalidException;
import com.yrootlab.onmaru.web.common.cursor.CursorPayload;
import com.yrootlab.onmaru.web.common.cursor.CursorSigningKey;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name="onmaru.discovery.api.enabled", havingValue="true")
final class KContentsCursor {
    private final CursorCodec codec;
    private final Clock clock;

    KContentsCursor(ObjectMapper json, SecretProvider secrets, Clock clock) {
        this.codec = new CursorCodec(json, CursorSigningKey.fromUtf8(secrets.get("oauth.client-secret").current()), clock);
        this.clock = clock;
    }

    record Position(UUID revision, String key, UUID id) { }

    Position decode(String cursor, String scope, String filter) {
        if (cursor == null || cursor.isBlank()) return null;
        Map<String,Object> claims = codec.decode(cursor, scope).claims();
        try {
            if (!filter.equals(claims.get("filter"))) throw new CursorInvalidException();
            return new Position(UUID.fromString((String) claims.get("revision")),
                    (String) claims.get("key"), UUID.fromString((String) claims.get("id")));
        } catch (RuntimeException invalid) {
            if (invalid instanceof CursorInvalidException) throw invalid;
            throw new CursorInvalidException(invalid);
        }
    }

    String encode(String scope, String filter, UUID revision, String key, UUID id) {
        return codec.encode(new CursorPayload(scope,
                Map.of("filter", filter, "revision", revision.toString(), "key", key, "id", id.toString()),
                clock.instant().plus(Duration.ofHours(24))));
    }
}
