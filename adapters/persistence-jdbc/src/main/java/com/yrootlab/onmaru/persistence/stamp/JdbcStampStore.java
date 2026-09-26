package com.yrootlab.onmaru.persistence.stamp;

import com.yrootlab.onmaru.persistence.jdbc.JdbcTransactionRunner;
import com.yrootlab.onmaru.stamp.CheckInRateLimitedException;
import com.yrootlab.onmaru.stamp.StampAwardPolicy;
import com.yrootlab.onmaru.stamp.StampAwardSummary;
import com.yrootlab.onmaru.stamp.StampBook;
import com.yrootlab.onmaru.stamp.StampBookItem;
import com.yrootlab.onmaru.stamp.StampBookSummary;
import com.yrootlab.onmaru.stamp.StampCheckIn;
import com.yrootlab.onmaru.stamp.StampCheckInResult;
import com.yrootlab.onmaru.stamp.StampConditionType;
import com.yrootlab.onmaru.stamp.StampDefinition;
import com.yrootlab.onmaru.stamp.StampRarity;
import com.yrootlab.onmaru.stamp.StampRegionRule;
import com.yrootlab.onmaru.stamp.StampStore;
import com.yrootlab.onmaru.stamp.VerifiedPlace;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class JdbcStampStore implements StampStore {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    private static final int DAILY_LIMIT = 30;

    private final DataSource dataSource;
    private final JdbcTransactionRunner transactions;
    private final StampAwardPolicy policy = new StampAwardPolicy();

    public JdbcStampStore(DataSource dataSource) {
        this(dataSource, new JdbcTransactionRunner(dataSource));
    }

    public JdbcStampStore(DataSource dataSource, JdbcTransactionRunner transactions) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<StampDefinition> definitions() {
        return transactions.execute(this::definitions);
    }

    @Override
    public StampCheckInResult record(UUID memberId, VerifiedPlace place, Instant now, int accuracyMeters) {
        return transactions.execute(connection -> {
            try {
                lockMember(connection, memberId);
                var bucket = bucket(now);
                var existing = findCheckIn(connection, memberId, place.internalPlaceId(), bucket);
                if (existing != null) {
                    var repeated = new StampCheckIn(
                            existing.id(), existing.placeId(), existing.checkedInAt(),
                            existing.distanceMeters(), true);
                    return new StampCheckInResult(repeated, List.of(), book(connection, memberId).summary());
                }
                enforceDailyLimit(connection, memberId, now);

                var checkIn = new StampCheckIn(UUID.randomUUID(), place.publicPlaceId(), now,
                        place.distanceMeters(), false);
                insertCheckIn(connection, checkIn.id(), memberId, place, now, bucket, accuracyMeters);

                var definitions = definitions(connection);
                var existingCodes = awardedCodes(connection, memberId);
                var selected = policy.newAwards(
                        definitions, regionRules(connection), place.regionCode(), now, existingCodes);
                var newAwards = new ArrayList<StampAwardSummary>();
                for (var definition : selected) {
                    if (insertAward(connection, memberId, checkIn.id(), definition.code(), now)) {
                        newAwards.add(new StampAwardSummary(
                                definition.code(), definition.name(), definition.sealText(),
                                definition.rarity(), now));
                    }
                }
                return new StampCheckInResult(checkIn, newAwards, book(connection, memberId).summary());
            } catch (CheckInRateLimitedException exception) {
                throw exception;
            } catch (SQLException exception) {
                throw new IllegalStateException("Stamp check-in transaction failed", exception);
            }
        });
    }

    @Override
    public StampBook book(UUID memberId) {
        return transactions.execute(connection -> book(connection, memberId));
    }

    private List<StampDefinition> definitions(Connection connection) {
        try (var statement = connection.prepareStatement("""
                SELECT code, name, description, condition_label, seal_text, icon_name, color,
                       rarity::text, condition_type::text, required_count, region_group,
                       sort_order, active
                FROM onmaru.stamp_definitions
                WHERE active = true
                ORDER BY sort_order
                """);
             var result = statement.executeQuery()) {
            var values = new ArrayList<StampDefinition>();
            while (result.next()) {
                Integer requiredCount = result.getObject("required_count") == null
                        ? null
                        : result.getInt("required_count");
                values.add(new StampDefinition(
                        result.getString("code"), result.getString("name"), result.getString("description"),
                        result.getString("condition_label"), result.getString("seal_text"),
                        result.getString("icon_name"), result.getString("color"),
                        StampRarity.valueOf(result.getString("rarity")),
                        StampConditionType.valueOf(result.getString("condition_type")),
                        requiredCount, result.getString("region_group"), result.getInt("sort_order"), true));
            }
            return List.copyOf(values);
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read stamp definitions", exception);
        }
    }

    private List<StampRegionRule> regionRules(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT stamp_code, region_code
                FROM onmaru.stamp_region_rules
                ORDER BY stamp_code, region_code
                """);
             var result = statement.executeQuery()) {
            var values = new ArrayList<StampRegionRule>();
            while (result.next()) {
                values.add(new StampRegionRule(result.getString("stamp_code"), result.getString("region_code")));
            }
            return List.copyOf(values);
        }
    }

    private StampBook book(Connection connection, UUID memberId) {
        try (var statement = connection.prepareStatement("""
                SELECT definition.code, definition.name, definition.description,
                       definition.condition_label, definition.seal_text, definition.icon_name,
                       definition.color, definition.rarity::text, definition.region_group,
                       definition.sort_order, award.awarded_at, check_in.public_place_id
                FROM onmaru.stamp_definitions definition
                LEFT JOIN onmaru.stamp_awards award
                  ON award.stamp_code = definition.code AND award.member_id = ?
                LEFT JOIN onmaru.stamp_check_ins check_in ON check_in.id = award.trigger_check_in_id
                WHERE definition.active = true
                ORDER BY definition.sort_order
                """)) {
            statement.setObject(1, memberId);
            try (var result = statement.executeQuery()) {
                var items = new ArrayList<StampBookItem>();
                var earnedCodes = new HashSet<String>();
                while (result.next()) {
                    var awardedAt = result.getObject("awarded_at", OffsetDateTime.class);
                    boolean collected = awardedAt != null;
                    var code = result.getString("code");
                    if (collected) {
                        earnedCodes.add(code);
                    }
                    items.add(new StampBookItem(
                            code, result.getString("name"), StampRarity.valueOf(result.getString("rarity")),
                            result.getString("region_group"), result.getString("condition_label"),
                            result.getString("description"), result.getString("seal_text"),
                            result.getString("icon_name"), result.getString("color"),
                            result.getInt("sort_order"), collected,
                            collected ? awardedAt.toInstant() : null,
                            result.getString("public_place_id")));
                }
                int regionCount = policy.visitedRegionCount(definitions(connection), earnedCodes);
                int requiredRegions = definitions(connection).stream()
                        .filter(definition -> definition.conditionType() == StampConditionType.REGION_COUNT)
                        .map(StampDefinition::requiredCount).filter(Objects::nonNull).findFirst().orElse(0);
                int total = items.size();
                int completion = total == 0 ? 0 : earnedCodes.size() * 100 / total;
                return new StampBook(
                        new StampBookSummary(earnedCodes.size(), total, regionCount, requiredRegions, completion),
                        items);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to read member stamp book", exception);
        }
    }

    private void lockMember(Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            statement.setString(1, "stamp|" + memberId);
            statement.execute();
        }
    }

    private StampCheckIn findCheckIn(Connection connection, UUID memberId, UUID placeId, Instant bucket)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id, public_place_id, checked_in_at, distance_meters
                FROM onmaru.stamp_check_ins
                WHERE member_id = ? AND place_id = ? AND check_in_bucket = ?
                """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, placeId);
            statement.setObject(3, atUtc(bucket));
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StampCheckIn(
                        result.getObject("id", UUID.class), result.getString("public_place_id"),
                        result.getObject("checked_in_at", OffsetDateTime.class).toInstant(),
                        result.getInt("distance_meters"), false);
            }
        }
    }

    private void insertCheckIn(
            Connection connection, UUID id, UUID memberId, VerifiedPlace place, Instant now,
            Instant bucket, int accuracyMeters) throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_check_ins
                    (id, member_id, place_id, public_place_id, region_code, checked_in_at,
                     check_in_bucket, distance_meters, accuracy_meters)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setObject(1, id);
            statement.setObject(2, memberId);
            statement.setObject(3, place.internalPlaceId());
            statement.setString(4, place.publicPlaceId());
            statement.setString(5, place.regionCode());
            statement.setObject(6, atUtc(now));
            statement.setObject(7, atUtc(bucket));
            statement.setInt(8, place.distanceMeters());
            statement.setInt(9, accuracyMeters);
            statement.executeUpdate();
        }
    }

    private Set<String> awardedCodes(Connection connection, UUID memberId) throws SQLException {
        try (var statement = connection.prepareStatement(
                "SELECT stamp_code FROM onmaru.stamp_awards WHERE member_id = ?")) {
            statement.setObject(1, memberId);
            try (var result = statement.executeQuery()) {
                var codes = new HashSet<String>();
                while (result.next()) {
                    codes.add(result.getString("stamp_code"));
                }
                return codes;
            }
        }
    }

    private boolean insertAward(
            Connection connection, UUID memberId, UUID checkInId, String stampCode, Instant now)
            throws SQLException {
        try (var statement = connection.prepareStatement("""
                INSERT INTO onmaru.stamp_awards
                    (id, member_id, stamp_code, trigger_check_in_id, awarded_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (member_id, stamp_code) DO NOTHING
                """)) {
            statement.setObject(1, UUID.randomUUID());
            statement.setObject(2, memberId);
            statement.setString(3, stampCode);
            statement.setObject(4, checkInId);
            statement.setObject(5, atUtc(now));
            return statement.executeUpdate() == 1;
        }
    }

    private void enforceDailyLimit(Connection connection, UUID memberId, Instant now) throws SQLException {
        var date = now.atZone(KOREA).toLocalDate();
        var start = date.atStartOfDay(KOREA).toInstant();
        var end = date.plusDays(1).atStartOfDay(KOREA).toInstant();
        try (var statement = connection.prepareStatement("""
                SELECT count(*)
                FROM onmaru.stamp_check_ins
                WHERE member_id = ? AND checked_in_at >= ? AND checked_in_at < ?
                """)) {
            statement.setObject(1, memberId);
            statement.setObject(2, atUtc(start));
            statement.setObject(3, atUtc(end));
            try (var result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) >= DAILY_LIMIT) {
                    throw new CheckInRateLimitedException(
                            Math.max(1, ChronoUnit.SECONDS.between(now, end)));
                }
            }
        }
    }

    private Instant bucket(Instant value) {
        long seconds = value.getEpochSecond();
        return Instant.ofEpochSecond(seconds - Math.floorMod(seconds, 900));
    }

    private OffsetDateTime atUtc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }
}
