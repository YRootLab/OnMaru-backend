package com.yrootlab.onmaru.catalog.externalplace;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class InMemoryExternalPlaceRegistry implements ExternalPlaceRegistry {

    private static final double LOCATION_CONFLICT_METERS = 1_000.0;
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    private final ExternalPlacePolicy policy;
    private final Supplier<UUID> idGenerator;
    private final Map<Key, ExternalPlace> places = new HashMap<>();

    public InMemoryExternalPlaceRegistry(ExternalPlacePolicy policy, Supplier<UUID> idGenerator) {
        this.policy = policy;
        this.idGenerator = idGenerator;
    }

    @Override
    public synchronized ExternalPlace resolveOrCreate(ExternalPlaceCandidate rawCandidate, String regionCode) {
        var candidate = policy.validate(rawCandidate);
        if (regionCode == null || regionCode.isBlank()) {
            throw new IllegalArgumentException("regionCode is required");
        }
        var key = new Key(candidate.provider(), candidate.externalId());
        var existing = places.get(key);
        if (existing != null) {
            if (distanceMeters(existing.lat(), existing.lng(), candidate.lat(), candidate.lng())
                    > LOCATION_CONFLICT_METERS) {
                throw new ExternalPlaceIdentityConflictException();
            }
            return existing;
        }
        var placeId = idGenerator.get();
        var place = new ExternalPlace(
                placeId,
                "p-ext-" + placeId.toString().replace("-", ""),
                candidate.name(),
                regionCode,
                candidate.lat(),
                candidate.lng(),
                true);
        places.put(key, place);
        return place;
    }

    private double distanceMeters(double leftLat, double leftLng, double rightLat, double rightLng) {
        double latDistance = Math.toRadians(rightLat - leftLat);
        double lngDistance = Math.toRadians(rightLng - leftLng);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(leftLat)) * Math.cos(Math.toRadians(rightLat))
                * Math.sin(lngDistance / 2) * Math.sin(lngDistance / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record Key(ExternalPlaceProvider provider, String externalId) {
    }
}
