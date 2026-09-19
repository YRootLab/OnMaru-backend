package com.yrootlab.onmaru.catalog.application.qualification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static java.util.Map.entry;

public final class SourceQualificationPolicy {

    private static final String ALLOWLIST_VERSION = "tourapi-category-allowlist-v3";
    private static final Pattern SECRET_FIELD_PATTERN = Pattern.compile(".*(key|token|secret|authorization|cookie|requesturl).*", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VOLATILE_HASH_FIELDS = Set.of("createdtime", "modifiedtime", "dist", "mlevel", "tel");

    private final Map<CategoryKey, CanonicalCategory> allowlist;

    private SourceQualificationPolicy(Map<CategoryKey, CanonicalCategory> allowlist) {
        this.allowlist = Map.copyOf(allowlist);
    }

    public static SourceQualificationPolicy withDefaultAllowlist() {
        return new SourceQualificationPolicy(Map.ofEntries(
                entry(new CategoryKey("12", "A02", "A0201", "A02010700"), CanonicalCategory.HANOK),
                entry(new CategoryKey("32", "B02", "B0201", "B02011600"), CanonicalCategory.HANOK_STAY),
                entry(new CategoryKey("39", "A05", "A0502", "A05020900"), CanonicalCategory.HANOK_CAFE),
                entry(new CategoryKey("12", "A02", "A0203", "A02030400"), CanonicalCategory.HANOK_EXPERIENCE),
                entry(new CategoryKey("38", "A04", "A0401", "A04010200"), CanonicalCategory.TRADITIONAL_MARKET),
                entry(new CategoryKey("12", "A02", "A0201", null), CanonicalCategory.HISTORIC_SITE),
                entry(new CategoryKey("38", "A04", "A0401", null), CanonicalCategory.TRADITIONAL_MARKET)
        ));
    }

    public QualificationResult qualify(SourceRecord row) {
        Optional<CanonicalCategory> category = categoryFor(row);
        if (category.isEmpty()) {
            return quarantine(row, "UNSUPPORTED_CATEGORY");
        }

        Double longitude = parseDouble(row.field("mapx"));
        Double latitude = parseDouble(row.field("mapy"));
        if (longitude == null || latitude == null) {
            return quarantine(row, "MISSING_COORDINATES");
        }
        if (!isKoreaCoordinate(longitude, latitude)) {
            return quarantine(row, "INVALID_COORDINATES");
        }

        CanonicalCandidate candidate = new CanonicalCandidate(
                row.recordKey(),
                row.field("contentid"),
                blankToFallback(row.field("title"), row.field("contentid")),
                category.orElseThrow(),
                longitude,
                latitude,
                normalizedHash(row),
                ALLOWLIST_VERSION
        );
        return QualificationResult.candidate(candidate);
    }

    private Optional<CanonicalCategory> categoryFor(SourceRecord row) {
        CategoryKey key = new CategoryKey(
                normalize(row.field("contenttypeid")),
                normalize(row.field("cat1")),
                normalize(row.field("cat2")),
                normalize(row.field("cat3"))
        );
        CanonicalCategory exact = allowlist.get(key);
        if (exact != null) {
            return Optional.of(exact);
        }
        CanonicalCategory cat2Level = allowlist.get(new CategoryKey(key.contentTypeId(), key.cat1(), key.cat2(), null));
        return Optional.ofNullable(cat2Level);
    }

    private QualificationResult quarantine(SourceRecord row, String errorCode) {
        return QualificationResult.quarantined(new QuarantineRecord(
                row.recordKey(),
                errorCode,
                sha256(canonicalPayload(row.fields(), List.of())),
                redact(row.fields())
        ));
    }

    private String normalizedHash(SourceRecord row) {
        return sha256(canonicalPayload(row.fields(), List.copyOf(VOLATILE_HASH_FIELDS)));
    }

    private String canonicalPayload(Map<String, String> fields, List<String> ignoredFields) {
        Set<String> ignored = Set.copyOf(ignoredFields);
        Map<String, String> sorted = new TreeMap<>();
        fields.forEach((key, value) -> {
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (!ignored.contains(normalizedKey)) {
                sorted.put(normalizedKey, normalize(value));
            }
        });

        StringBuilder builder = new StringBuilder();
        sorted.forEach((key, value) -> builder.append(key).append('=').append(value).append('\n'));
        return builder.toString();
    }

    private Map<String, String> redact(Map<String, String> fields) {
        Map<String, String> redacted = new LinkedHashMap<>();
        fields.forEach((key, value) -> {
            if (SECRET_FIELD_PATTERN.matcher(key).matches()) {
                redacted.put(key, "<REDACTED>");
            } else {
                redacted.put(key, value);
            }
        });
        return Map.copyOf(redacted);
    }

    private boolean isKoreaCoordinate(double longitude, double latitude) {
        return longitude >= 124.0 && longitude <= 132.0 && latitude >= 33.0 && latitude <= 39.0;
    }

    private Double parseDouble(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private String blankToFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte next : hash) {
                builder.append(String.format("%02x", next));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private record CategoryKey(String contentTypeId, String cat1, String cat2, String cat3) {
    }
}
