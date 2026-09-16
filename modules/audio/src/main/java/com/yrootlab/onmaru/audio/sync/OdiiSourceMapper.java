package com.yrootlab.onmaru.audio.sync;

import com.yrootlab.onmaru.catalog.application.tags.ContentTagExtractor;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagPipeline;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagPipelineResult;
import com.yrootlab.onmaru.catalog.application.tags.ContentTagSource;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class OdiiSourceMapper {

    private static final String PROVIDER = "KTO_ODII";
    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("ko", "en", "ja", "zh-CN", "zh-TW");
    private static final DateTimeFormatter PROVIDER_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final int MAX_CONTENT_TAGS = 7;

    private final ContentTagPipeline contentTagPipeline;

    public OdiiSourceMapper() {
        this(ContentTagPipeline.defaultPipeline());
    }

    public OdiiSourceMapper(ContentTagExtractor contentTagExtractor) {
        this(ContentTagPipeline.of(contentTagExtractor));
    }

    public OdiiSourceMapper(ContentTagPipeline contentTagPipeline) {
        this.contentTagPipeline = contentTagPipeline;
    }

    public OdiiMappedStory map(OdiiSourceStory source) {
        String language = required(source.langCode(), "langCode");
        if (!SUPPORTED_LANGUAGES.contains(language)) {
            throw new OdiiMappingException("unsupported langCode: " + language);
        }

        var spotIdentity = new OdiiSpotIdentity(
                PROVIDER,
                required(source.tid(), "tid"),
                required(source.tlid(), "tlid"),
                language
        );
        var storyIdentity = new OdiiStoryIdentity(
                PROVIDER,
                required(source.stid(), "stid"),
                required(source.stlid(), "stlid"),
                language
        );
        String sourceTitle = required(source.title(), "title");
        String storyTitle = firstPresent(source.audioTitle(), sourceTitle);
        String script = blankToNull(source.script());
        BigDecimal longitude = coordinate(source.mapX(), "mapX", -180, 180);
        BigDecimal latitude = coordinate(source.mapY(), "mapY", -90, 90);
        Integer duration = duration(source.playTime());
        Instant modifiedAt = timestamp(source.modifiedTime(), "modifiedtime");
        String audioUrl = blankToNull(source.audioUrl());
        String imageUrl = blankToNull(source.imageUrl());

        var spot = new OdiiSpotVersion(
                spotIdentity,
                sourceTitle,
                longitude,
                latitude,
                modifiedAt,
                AudioStatus.ACTIVE,
                hash(spotIdentity, sourceTitle, longitude, latitude, modifiedAt)
        );
        var contentTagResult = contentTags(sourceTitle, storyTitle, script);
        var story = new OdiiStoryVersion(
                storyIdentity,
                spotIdentity,
                storyTitle,
                script,
                script == null ? TranscriptProvenance.MISSING : TranscriptProvenance.OFFICIAL,
                audioUrl,
                imageUrl,
                duration,
                modifiedAt,
                AudioStatus.ACTIVE,
                contentTagResult.publicLabels(),
                contentTagResult.qualityReport(),
                hash(storyIdentity, spotIdentity, storyTitle, script, audioUrl, imageUrl, duration, modifiedAt)
        );
        return new OdiiMappedStory(spot, story);
    }

    private ContentTagPipelineResult contentTags(
            String sourceTitle,
            String storyTitle,
            String script) {
        return contentTagPipeline.generate(ContentTagSource.of(
                storyTitle,
                null,
                sourceTitle,
                script == null ? List.of() : List.of(script)), MAX_CONTENT_TAGS);
    }

    private String required(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new OdiiMappingException(field + " is required");
        }
        return normalized;
    }

    private String firstPresent(String preferred, String fallback) {
        String normalized = blankToNull(preferred);
        return normalized == null ? fallback : normalized;
    }

    private String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private Integer duration(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        try {
            int duration = Integer.parseInt(normalized);
            if (duration < 0) {
                throw new OdiiMappingException("playTime must be non-negative");
            }
            return duration;
        } catch (NumberFormatException exception) {
            throw new OdiiMappingException("playTime must be an integer", exception);
        }
    }

    private BigDecimal coordinate(String value, String field, int minimum, int maximum) {
        String normalized = required(value, field);
        try {
            BigDecimal coordinate = new BigDecimal(normalized);
            if (coordinate.compareTo(BigDecimal.valueOf(minimum)) < 0
                    || coordinate.compareTo(BigDecimal.valueOf(maximum)) > 0) {
                throw new OdiiMappingException(field + " is outside the supported range");
            }
            return coordinate;
        } catch (NumberFormatException exception) {
            throw new OdiiMappingException(field + " must be decimal", exception);
        }
    }

    private Instant timestamp(String value, String field) {
        try {
            return LocalDateTime.parse(required(value, field), PROVIDER_TIMESTAMP).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException exception) {
            throw new OdiiMappingException(field + " must use yyyyMMddHHmmss", exception);
        }
    }

    private String hash(Object... values) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
