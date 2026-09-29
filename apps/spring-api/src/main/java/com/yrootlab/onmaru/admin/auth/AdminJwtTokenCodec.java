package com.yrootlab.onmaru.admin.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretBundle;
import com.yrootlab.onmaru.config.secrets.SecretProvider;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AdminJwtTokenCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String BEARER = "Bearer ";

    private final SecretProvider secrets;
    private final String secretName;
    private final String issuer;
    private final String audience;
    private final Duration lifetime;
    private final Clock clock;
    private final AdminJtiRevocationStore revokedJtis;

    public AdminJwtTokenCodec(
            SecretProvider secrets,
            String secretName,
            String issuer,
            String audience,
            Duration lifetime,
            Clock clock) {
        this(secrets, secretName, issuer, audience, lifetime, clock, new AdminJtiRevocationStore() {
            @Override
            public void revoke(String jti, Instant expiresAt) {
                // Backward-compatible no-op until the application wiring opts in.
            }

            @Override
            public boolean isRevoked(String jti, Instant now) {
                return false;
            }
        });
    }

    public AdminJwtTokenCodec(
            SecretProvider secrets,
            String secretName,
            String issuer,
            String audience,
            Duration lifetime,
            Clock clock,
            AdminJtiRevocationStore revokedJtis) {
        this.secrets = secrets;
        this.secretName = secretName;
        this.issuer = issuer;
        this.audience = audience;
        this.lifetime = lifetime;
        this.clock = clock;
        this.revokedJtis = revokedJtis;
    }

    public String issue(AdminPrincipal principal) {
        return issueWithLifetimeAndSecret(principal, lifetime, secrets.get(secretName).current());
    }

    String issueWithSecret(AdminPrincipal principal, String secret) {
        return issueWithLifetimeAndSecret(principal, lifetime, secret);
    }

    String issueWithLifetime(AdminPrincipal principal, Duration requestedLifetime) {
        return issueWithLifetimeAndSecret(principal, requestedLifetime, secrets.get(secretName).current());
    }

    public AdminPrincipal verify(String authorizationOrToken) {
        return verifyToken(authorizationOrToken).principal();
    }

    public AdminAccessToken verifyToken(String authorizationOrToken) {
        String token = authorizationOrToken == null
                ? ""
                : authorizationOrToken.startsWith(BEARER)
                ? authorizationOrToken.substring(BEARER.length())
                : authorizationOrToken;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3 || parts[0].isBlank() || parts[1].isBlank() || parts[2].isBlank()) {
                throw new AdminAuthenticationException();
            }
            Map<String, Object> header = readMap(parts[0]);
            if (!"HS256".equals(header.get("alg")) || !"JWT".equals(header.get("typ"))) {
                throw new AdminAuthenticationException();
            }
            SecretBundle bundle = secrets.get(secretName);
            boolean signatureMatches = matchesSignature(parts[0] + "." + parts[1], parts[2], bundle.current())
                    || bundle.previous().map(previous -> matchesSignature(parts[0] + "." + parts[1], parts[2], previous)).orElse(false);
            if (!signatureMatches) {
                throw new AdminAuthenticationException();
            }
            Map<String, Object> claims = readMap(parts[1]);
            long now = Instant.now(clock).getEpochSecond();
            long expiresAt = number(claims, "exp");
            if (!issuer.equals(claims.get("iss"))
                    || !audience.equals(claims.get("aud"))
                    || expiresAt <= now
                    || number(claims, "iat") > now + 30) {
                throw new AdminAuthenticationException();
            }
            AdminPrincipal principal = new AdminPrincipal(
                    UUID.fromString(string(claims, "sub")),
                    string(claims, "email"),
                    AdminRole.valueOf(string(claims, "role")));
            String jti = string(claims, "jti");
            if (revokedJtis.isRevoked(jti, Instant.ofEpochSecond(now))) {
                throw new AdminAuthenticationException();
            }
            return new AdminAccessToken(principal, jti, Instant.ofEpochSecond(expiresAt));
        } catch (AdminAuthenticationException exception) {
            throw exception;
        } catch (RuntimeException | IOException exception) {
            throw new AdminAuthenticationException();
        }
    }

    private String issueWithLifetimeAndSecret(AdminPrincipal principal, Duration tokenLifetime, String secret) {
        Instant now = Instant.now(clock);
        Map<String, Object> header = Map.of("alg", "HS256", "typ", "JWT");
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("aud", audience);
        claims.put("sub", principal.id().toString());
        claims.put("email", principal.email());
        claims.put("role", principal.role().name());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(tokenLifetime).getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());
        String input = encode(header) + "." + encode(claims);
        return input + "." + sign(secret, input);
    }

    private boolean matchesSignature(String input, String expected, String secret) {
        return java.security.MessageDigest.isEqual(
                sign(secret, input).getBytes(StandardCharsets.US_ASCII),
                expected.getBytes(StandardCharsets.US_ASCII));
    }

    private static String encode(Object value) {
        try {
            return ENCODER.encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to encode admin token", exception);
        }
    }

    private static Map<String, Object> readMap(String encoded) throws IOException {
        return MAPPER.readValue(DECODER.decode(encoded), new TypeReference<>() {});
    }

    private static String sign(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return ENCODER.encodeToString(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("failed to sign admin token", exception);
        }
    }

    private static String string(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new AdminAuthenticationException();
        }
        return text;
    }

    private static long number(Map<String, Object> claims, String name) {
        Object value = claims.get(name);
        if (!(value instanceof Number number)) {
            throw new AdminAuthenticationException();
        }
        return number.longValue();
    }
}
