package com.yrootlab.onmaru.web.stamp;

import com.yrootlab.onmaru.OnMaruApplication;
import com.yrootlab.onmaru.identity.oauth.InMemoryIdentityStore;
import com.yrootlab.onmaru.identity.oauth.SessionRecord;
import com.yrootlab.onmaru.identity.oauth.TokenHasher;
import com.yrootlab.onmaru.stamp.CheckInPlaceLookup;
import com.yrootlab.onmaru.stamp.InMemoryStampStore;
import com.yrootlab.onmaru.stamp.VerifiedPlace;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = {OnMaruApplication.class, StampWebBoundaryTests.TestStampConfiguration.class},
        properties = "onmaru.secrets.source=fake")
@AutoConfigureMockMvc
class StampWebBoundaryTests {

    private static final String SESSION = "stamp-member-session";
    private static final String PLACE = "p-bukchon-house";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InMemoryIdentityStore identityStore;

    @Autowired
    private InMemoryStampStore stampStore;

    @Autowired
    private TestStampPlaceLookup placeLookup;

    @Autowired
    private Clock clock;

    private final TokenHasher hasher = new TokenHasher("fake-oauth-client-secret-current");

    @BeforeEach
    void setUp() {
        identityStore.clear();
        stampStore.clear();
        placeLookup.clear();
        var memberId = identityStore.createMember(clock.instant());
        identityStore.saveSession(new SessionRecord(
                hasher.hash(SESSION), memberId, clock.instant(), clock.instant(), clock.instant().plusSeconds(3600)));
        placeLookup.put(PLACE, "kr-11-jongno-bukchon", 42);
    }

    @Test
    void publicCatalogAndPrivateBookHaveSeparatePrivacyBoundaries() throws Exception {
        mockMvc.perform(get("/api/v1/stamps"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("1.2"))
                .andExpect(jsonPath("$.stamps.length()").value(12))
                .andExpect(jsonPath("$.stamps[0].code").value("stamp_bukchon"))
                .andExpect(jsonPath("$.stamps[0].collected").doesNotExist());

        mockMvc.perform(get("/api/v1/me/stamp-book"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));

        mockMvc.perform(get("/api/v1/me/stamp-book")
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", SESSION)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(jsonPath("$.summary.collectedCount").value(0))
                .andExpect(jsonPath("$.stamps.length()").value(12));
    }

    @Test
    void createsReplaysAndDeduplicatesCheckInsWithoutEchoingCoordinates() throws Exception {
        var key = "00000000-0000-0000-0000-000000000262";
        var response = checkIn(PLACE, key, 37.5826, 126.9831, 18.4)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/check-ins/")))
                .andExpect(jsonPath("$.checkIn.alreadyCheckedIn").value(false))
                .andExpect(jsonPath("$.checkIn.distanceMeters").value(42))
                .andExpect(jsonPath("$.newAwards[0].code").value("stamp_bukchon"))
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.accuracyMeters").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        checkIn(PLACE, key, 37.5826, 126.9831, 18.4)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.checkIn.alreadyCheckedIn").value(false))
                .andExpect(jsonPath("$").value(not("")));

        checkIn(PLACE, "00000000-0000-0000-0000-000000000263", 37.5826, 126.9831, 18.4)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkIn.alreadyCheckedIn").value(true))
                .andExpect(jsonPath("$.newAwards.length()").value(0));

        org.assertj.core.api.Assertions.assertThat(response).doesNotContain("37.5826", "126.9831", "18.4");
    }

    @Test
    void validatesAuthenticationCsrfIdempotencyAndLocationFailures() throws Exception {
        mockMvc.perform(post("/api/v1/places/{placeId}/check-ins", PLACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(37.5826, 126.9831, 18.4))
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/places/{placeId}/check-ins", PLACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(37.5826, 126.9831, 18.4))
                        .cookie(new jakarta.servlet.http.Cookie("__Host-onmaru-session", SESSION))
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));

        checkIn(PLACE, null, 37.5826, 126.9831, 18.4)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_MISSING"));
        checkIn(PLACE, "not-a-uuid", 37.5826, 126.9831, 18.4)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
        checkIn(PLACE, UUID.randomUUID().toString(), 91, 126.9831, 18.4)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("latitude"));
        checkIn("p-not-found", UUID.randomUUID().toString(), 37.5826, 126.9831, 18.4)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        checkIn(PLACE, UUID.randomUUID().toString(), 37.5826, 126.9831, 101)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("LOCATION_ACCURACY_TOO_LOW"));

        placeLookup.put("p-far", "kr-11-jongno", 319);
        checkIn("p-far", UUID.randomUUID().toString(), 37.5826, 126.9831, 20)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OUTSIDE_CHECK_IN_RADIUS"))
                .andExpect(jsonPath("$.details.allowedRadiusMeters").value(200))
                .andExpect(jsonPath("$.details.distanceMeters").doesNotExist());

        mockMvc.perform(post("/api/v1/places/{placeId}/check-ins", PLACE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accuracyMeters\":18.4}")
                        .cookie(
                                new jakarta.servlet.http.Cookie("__Host-onmaru-session", SESSION),
                                new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                        .header("X-CSRF-TOKEN", "csrf-token")
                        .header("Idempotency-Key", UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.field").value("latitude"));
    }

    @Test
    void rejectsIdempotencyConflictAndMapsTemporaryFailure() throws Exception {
        var key = "00000000-0000-0000-0000-000000000264";
        checkIn(PLACE, key, 37.5826, 126.9831, 18.4).andExpect(status().isCreated());
        checkIn(PLACE, key, 37.5827, 126.9831, 18.4)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        placeLookup.fail(true);
        checkIn(PLACE, UUID.randomUUID().toString(), 37.5826, 126.9831, 18.4)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }

    @Test
    void limitsSuccessfulCheckInsToThirtyPerKoreaDay() throws Exception {
        for (int index = 0; index < 31; index++) {
            var placeId = "p-limit-" + index;
            placeLookup.put(placeId, "kr-11-jongno", 10);
            var action = checkIn(placeId, UUID.randomUUID().toString(), 37.5, 127.0, 10);
            if (index < 30) {
                action.andExpect(status().isCreated());
            } else {
                action.andExpect(status().isTooManyRequests())
                        .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                        .andExpect(jsonPath("$.code").value("CHECK_IN_RATE_LIMITED"))
                        .andExpect(jsonPath("$.details.retryAfterSeconds").isNumber());
            }
        }
    }

    private org.springframework.test.web.servlet.ResultActions checkIn(
            String placeId, String key, double latitude, double longitude, double accuracy) throws Exception {
        var request = post("/api/v1/places/{placeId}/check-ins", placeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(latitude, longitude, accuracy))
                .cookie(
                        new jakarta.servlet.http.Cookie("__Host-onmaru-session", SESSION),
                        new jakarta.servlet.http.Cookie("__Host-onmaru-csrf", "csrf-token"))
                .header("X-CSRF-TOKEN", "csrf-token");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mockMvc.perform(request);
    }

    private String body(double latitude, double longitude, double accuracy) {
        return "{\"latitude\":" + latitude + ",\"longitude\":" + longitude
                + ",\"accuracyMeters\":" + accuracy + "}";
    }

    @TestConfiguration
    static class TestStampConfiguration {
        @Bean
        @Primary
        TestStampPlaceLookup testStampPlaceLookup() {
            return new TestStampPlaceLookup();
        }
    }

    static final class TestStampPlaceLookup implements CheckInPlaceLookup {
        private final Map<String, VerifiedPlace> places = new HashMap<>();
        private boolean fail;

        void put(String publicId, String regionCode, int distanceMeters) {
            places.put(publicId, new VerifiedPlace(
                    UUID.nameUUIDFromBytes(publicId.getBytes(StandardCharsets.UTF_8)),
                    publicId, regionCode, distanceMeters));
        }

        void fail(boolean value) {
            fail = value;
        }

        void clear() {
            places.clear();
            fail = false;
        }

        @Override
        public Optional<VerifiedPlace> verify(String placeId, double latitude, double longitude) {
            if (fail) {
                throw new IllegalStateException("temporary catalog failure");
            }
            return Optional.ofNullable(places.get(placeId));
        }
    }
}
