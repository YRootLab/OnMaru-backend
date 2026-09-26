package com.yrootlab.onmaru.web.stamp;

import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.persistence.stamp.JdbcCheckInPlaceLookup;
import com.yrootlab.onmaru.persistence.stamp.JdbcStampStore;
import com.yrootlab.onmaru.stamp.CheckInPlaceLookup;
import com.yrootlab.onmaru.stamp.InMemoryStampStore;
import com.yrootlab.onmaru.stamp.StampService;
import com.yrootlab.onmaru.stamp.StampStore;
import com.yrootlab.onmaru.stamp.VerifiedPlace;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Configuration
class StampConfiguration {

    @Bean
    @Profile("!production")
    InMemoryStampStore inMemoryStampStore() {
        return new InMemoryStampStore();
    }

    @Bean
    @Profile("!production")
    CheckInPlaceLookup demoStampPlaceLookup() {
        var places = Map.of(
                "p-jeonju-hanok-village", new DemoPlace("kr-45-jeonju", 35.8151, 127.1530),
                "p-bukchon-hanok-cafe", new DemoPlace("kr-11-jongno", 37.5824, 126.9836));
        return (placeId, latitude, longitude) -> Optional.ofNullable(places.get(placeId))
                .map(place -> new VerifiedPlace(
                        UUID.nameUUIDFromBytes(placeId.getBytes(StandardCharsets.UTF_8)),
                        placeId,
                        place.regionCode(),
                        (int) Math.ceil(distanceMeters(latitude, longitude, place.latitude(), place.longitude()))));
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    CheckInPlaceLookup jdbcStampPlaceLookup(
            DataSource dataSource, JdbcTransactionRunner jdbcTransactionRunner) {
        return new JdbcCheckInPlaceLookup(dataSource, jdbcTransactionRunner);
    }

    @Bean
    @Profile("production")
    @ConditionalOnBean(DataSource.class)
    StampStore jdbcStampStore(DataSource dataSource, JdbcTransactionRunner jdbcTransactionRunner) {
        return new JdbcStampStore(dataSource, jdbcTransactionRunner);
    }

    @Bean
    StampService stampService(CheckInPlaceLookup placeLookup, StampStore store, Clock clock) {
        return new StampService(placeLookup, store, clock);
    }

    private static double distanceMeters(double fromLat, double fromLon, double toLat, double toLon) {
        double earthRadius = 6_371_000;
        double latitudeDelta = Math.toRadians(toLat - fromLat);
        double longitudeDelta = Math.toRadians(toLon - fromLon);
        double firstLatitude = Math.toRadians(fromLat);
        double secondLatitude = Math.toRadians(toLat);
        double a = Math.sin(latitudeDelta / 2) * Math.sin(latitudeDelta / 2)
                + Math.cos(firstLatitude) * Math.cos(secondLatitude)
                * Math.sin(longitudeDelta / 2) * Math.sin(longitudeDelta / 2);
        return earthRadius * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private record DemoPlace(String regionCode, double latitude, double longitude) {
    }
}
