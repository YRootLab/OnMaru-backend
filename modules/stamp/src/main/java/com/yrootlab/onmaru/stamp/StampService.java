package com.yrootlab.onmaru.stamp;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class StampService {

    public static final int BASE_RADIUS_METERS = 200;
    public static final int MAX_ACCURACY_METERS = 100;

    private final CheckInPlaceLookup placeLookup;
    private final StampStore store;
    private final Clock clock;

    public StampService(CheckInPlaceLookup placeLookup, StampStore store, Clock clock) {
        this.placeLookup = Objects.requireNonNull(placeLookup, "placeLookup");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public java.util.List<StampDefinition> definitions() {
        return store.definitions();
    }

    public StampBook book(UUID memberId) {
        return store.book(Objects.requireNonNull(memberId, "memberId"));
    }

    public StampCheckInResult checkIn(UUID memberId, CheckInCommand command) {
        Objects.requireNonNull(memberId, "memberId");
        validate(command);
        var place = placeLookup.verify(command.placeId(), command.latitude(), command.longitude())
                .orElseThrow(CheckInPlaceNotFoundException::new);
        if (place.distanceMeters() - command.accuracyMeters() > BASE_RADIUS_METERS) {
            throw new OutsideCheckInRadiusException();
        }
        return store.record(memberId, place, clock.instant(), (int) Math.ceil(command.accuracyMeters()));
    }

    private void validate(CheckInCommand command) {
        if (command == null) {
            throw new CheckInInputInvalidException("body");
        }
        if (command.placeId() == null || command.placeId().isBlank()) {
            throw new CheckInInputInvalidException("placeId");
        }
        if (!Double.isFinite(command.latitude()) || command.latitude() < -90 || command.latitude() > 90) {
            throw new CheckInInputInvalidException("latitude");
        }
        if (!Double.isFinite(command.longitude()) || command.longitude() < -180 || command.longitude() > 180) {
            throw new CheckInInputInvalidException("longitude");
        }
        if (!Double.isFinite(command.accuracyMeters()) || command.accuracyMeters() <= 0) {
            throw new CheckInInputInvalidException("accuracyMeters");
        }
        if (command.accuracyMeters() > MAX_ACCURACY_METERS) {
            throw new LocationAccuracyTooLowException();
        }
    }
}
