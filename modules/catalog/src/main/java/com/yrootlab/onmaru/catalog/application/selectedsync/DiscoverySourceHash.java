package com.yrootlab.onmaru.catalog.application.selectedsync;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Stable SHA-256 projections; transport timestamps are deliberately excluded. */
public final class DiscoverySourceHash {
    public static final String SCHEMA_VERSION = "discovery-source-hash-v1";
    private static final Set<String> LIST_FIELDS = Set.of("contentid", "contenttypeid", "title", "addr1", "addr2",
            "areacode", "sigungucode", "lclssystm1", "lclssystm2", "lclssystm3", "mapx", "mapy", "firstimage", "firstimage2", "cpyrhtdivcd");
    private static final Set<String> DETAIL_FIELDS = Set.of("overview", "detail", "homepage", "usetime", "restdate", "parking", "infocenter");

    private DiscoverySourceHash() { }

    public static String list(SourceRecord row) { return hash(row.fields(), LIST_FIELDS); }
    public static String detail(SourceRecord row) { return hash(row.fields(), DETAIL_FIELDS); }

    private static String hash(Map<String, String> fields, Set<String> selected) {
        var sorted = new TreeMap<String, String>();
        fields.forEach((name, value) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (selected.contains(key)) sorted.put(key, value == null ? "" : value.strip());
        });
        var canonical = new StringBuilder(SCHEMA_VERSION).append('\n');
        selected.stream().sorted().forEach(key -> canonical.append(key).append('=').append(sorted.getOrDefault(key, "")).append('\n'));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            var result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
