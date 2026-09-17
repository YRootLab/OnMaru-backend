package com.yrootlab.onmaru.catalog.application.tags;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class ContentTagPipeline {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final ContentTagExtractor extractor;
    private final ContentTagLexicon lexicon;
    private final ContentTagQualityPolicy qualityPolicy;

    private ContentTagPipeline(
            ContentTagExtractor extractor,
            ContentTagLexicon lexicon,
            ContentTagQualityPolicy qualityPolicy) {
        this.extractor = extractor;
        this.lexicon = lexicon;
        this.qualityPolicy = qualityPolicy;
    }

    public static ContentTagPipeline defaultPipeline() {
        var lexicon = ContentTagLexicon.defaultLexicon();
        return new ContentTagPipeline(
                ContentTagExtractor.using(lexicon),
                lexicon,
                ContentTagQualityPolicy.defaultPolicy());
    }

    public static ContentTagPipeline of(ContentTagExtractor extractor) {
        if (extractor == null) {
            throw new IllegalArgumentException("extractor must not be null");
        }
        return new ContentTagPipeline(
                extractor,
                ContentTagLexicon.defaultLexicon(),
                ContentTagQualityPolicy.defaultPolicy());
    }

    public ContentTagPipelineResult generate(ContentTagSource source, int maxTags) {
        return generate(source, maxTags, List.of());
    }

    public ContentTagPipelineResult generate(
            ContentTagSource source,
            int maxTags,
            List<ContentTagOverride> overrides
    ) {
        if (source == null || maxTags < 1) {
            return emptyResult(sourceHash(source), 0);
        }

        var generated = extractor.extractRanked(source, maxTags * 2);
        var hiddenLabels = overrideLabels(overrides, ContentTagOverrideAction.HIDE);
        var pinnedLabels = overrideLabels(overrides, ContentTagOverrideAction.PIN);
        var filtered = new ArrayList<ContentTag>();
        var labels = new LinkedHashSet<String>();
        int removedGenericCount = 0;
        int hiddenOverrideCount = 0;

        for (ContentTag tag : generated) {
            String canonical = canonicalLabel(tag.label());
            if (generic(canonical)) {
                removedGenericCount++;
                continue;
            }
            if (hiddenLabels.contains(canonical)) {
                hiddenOverrideCount++;
                continue;
            }
            if (labels.add(canonical)) {
                filtered.add(tag);
            }
        }

        var publicLabels = new ArrayList<String>();
        int pinnedOverrideCount = 0;
        for (String pinned : pinnedLabels) {
            if (!hiddenLabels.contains(canonicalLabel(pinned)) && addLabel(publicLabels, pinned)) {
                pinnedOverrideCount++;
            }
        }
        for (ContentTag tag : filtered) {
            if (publicLabels.size() >= maxTags) {
                break;
            }
            addLabel(publicLabels, tag.label());
        }
        if (publicLabels.size() > maxTags) {
            publicLabels = new ArrayList<>(publicLabels.subList(0, maxTags));
        }

        var report = new ContentTagQualityReport(
                generated.size(),
                publicLabels.size(),
                removedGenericCount,
                hiddenOverrideCount,
                pinnedOverrideCount,
                publicLabels.isEmpty(),
                qualityPolicy.lowConfidence(publicLabels.size(), maxTags),
                warnings(publicLabels, removedGenericCount, hiddenOverrideCount));
        return new ContentTagPipelineResult(
                publicLabels,
                filtered,
                sourceHash(source),
                ContentTagExtractor.ALGORITHM_VERSION,
                report);
    }

    private ContentTagPipelineResult emptyResult(String sourceHash, int generatedCount) {
        return new ContentTagPipelineResult(
                List.of(),
                List.of(),
                sourceHash,
                ContentTagExtractor.ALGORITHM_VERSION,
                new ContentTagQualityReport(
                        generatedCount,
                        0,
                        0,
                        0,
                        0,
                        true,
                        true,
                        List.of("EMPTY_RESULT")));
    }

    private Set<String> overrideLabels(List<ContentTagOverride> overrides, ContentTagOverrideAction action) {
        if (overrides == null || overrides.isEmpty()) {
            return Set.of();
        }
        var labels = new LinkedHashSet<String>();
        for (ContentTagOverride override : overrides) {
            if (override.action() == action) {
                labels.add(canonicalLabel(override.label()));
            }
        }
        return labels;
    }

    private boolean addLabel(List<String> labels, String label) {
        String normalized = normalizeLabel(label);
        if (normalized.isBlank() || generic(canonicalLabel(normalized))) {
            return false;
        }
        for (String existing : labels) {
            if (canonicalLabel(existing).equals(canonicalLabel(normalized))) {
                return false;
            }
        }
        labels.add(normalized);
        return true;
    }

    private boolean generic(String canonicalLabel) {
        if (lexicon.genericLabels().contains(canonicalLabel)) {
            return true;
        }
        if (canonicalLabel.contains(" ")) {
            var parts = List.of(canonicalLabel.split(" "));
            if (parts.stream().allMatch(lexicon.genericLabels()::contains)) {
                return true;
            }
        }
        if (canonicalLabel.chars().allMatch(Character::isDigit)) {
            return true;
        }
        return canonicalLabel.length() < 2;
    }

    private List<String> warnings(
            List<String> publicLabels,
            int removedGenericCount,
            int hiddenOverrideCount) {
        var warnings = new ArrayList<String>();
        if (publicLabels.isEmpty()) {
            warnings.add("EMPTY_RESULT");
        }
        if (publicLabels.size() < 3) {
            warnings.add("LOW_CONFIDENCE");
        }
        if (qualityPolicy.genericHeavy(removedGenericCount)) {
            warnings.add("GENERIC_HEAVY");
        }
        if (removedGenericCount > 0) {
            warnings.add("GENERIC_TAGS_REMOVED");
        }
        if (hiddenOverrideCount > 0) {
            warnings.add("OVERRIDE_HIDE_APPLIED");
        }
        return warnings;
    }

    private String sourceHash(ContentTagSource source) {
        var fields = new LinkedHashMap<String, List<String>>();
        fields.put("title", List.of(nullToBlank(source == null ? null : source.title())));
        fields.put("category", List.of(nullToBlank(source == null ? null : source.category())));
        fields.put("body", List.of(nullToBlank(source == null ? null : source.body())));
        fields.put("highlights", source == null ? List.of() : source.highlights());
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0x1F);
                for (String value : entry.getValue()) {
                    digest.update(normalizeLabel(value).getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) 0x1E);
                }
                digest.update((byte) 0x1D);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String canonicalLabel(String label) {
        return normalizeLabel(label).toLowerCase(Locale.ROOT);
    }

    private String normalizeLabel(String label) {
        if (label == null) {
            return "";
        }
        return WHITESPACE.matcher(Normalizer.normalize(label.trim(), Normalizer.Form.NFC))
                .replaceAll(" ");
    }

    private String nullToBlank(String value) {
        return value == null ? "" : value;
    }
}
