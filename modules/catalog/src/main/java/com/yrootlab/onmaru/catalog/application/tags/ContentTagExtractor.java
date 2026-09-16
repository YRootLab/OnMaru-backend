package com.yrootlab.onmaru.catalog.application.tags;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public final class ContentTagExtractor {

    public static final String ALGORITHM_VERSION = "content-tags-v2";
    private static final int DEFAULT_MAX_TAG_LENGTH = 24;
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[^0-9A-Za-z가-힣]+");
    private final ContentTagLexicon lexicon;

    private ContentTagExtractor(ContentTagLexicon lexicon) {
        this.lexicon = lexicon;
    }

    public static ContentTagExtractor defaultExtractor() {
        return new ContentTagExtractor(ContentTagLexicon.defaultLexicon());
    }

    public static ContentTagExtractor using(ContentTagLexicon lexicon) {
        if (lexicon == null) {
            throw new IllegalArgumentException("lexicon must not be null");
        }
        return new ContentTagExtractor(lexicon);
    }

    public List<String> extract(ContentTagSource source, int maxTags) {
        return extractRanked(source, maxTags).stream()
                .map(ContentTag::label)
                .toList();
    }

    public List<ContentTag> extractRanked(ContentTagSource source, int maxTags) {
        if (source == null || maxTags < 1) {
            return List.of();
        }
        var candidates = new LinkedHashMap<String, Candidate>();
        addWeighted(candidates, source.title(), 3.0, ContentTagSourceType.TITLE);
        addWeighted(candidates, source.category(), 0.5, ContentTagSourceType.CATEGORY);
        source.highlights().forEach(highlight -> addWeighted(
                candidates, highlight, 4.0, ContentTagSourceType.HIGHLIGHT));
        addWeighted(candidates, source.body(), 1.0, ContentTagSourceType.BODY);

        return candidates.values().stream()
                .filter(candidate -> candidate.score > 0)
                .sorted(Comparator
                        .comparingDouble(Candidate::score).reversed()
                        .thenComparingInt(Candidate::firstSeen)
                        .thenComparing(Candidate::label))
                .map(candidate -> new ContentTag(
                        candidate.label(),
                        candidate.score(),
                        candidate.firstSeen(),
                        candidate.source(),
                        ALGORITHM_VERSION))
                .limit(maxTags)
                .toList();
    }

    private void addWeighted(
            Map<String, Candidate> candidates,
            String text,
            double weight,
            ContentTagSourceType sourceType) {
        var tokens = tokenize(text);
        if (tokens.isEmpty()) {
            return;
        }
        for (int index = 0; index < tokens.size(); index++) {
            addCandidate(candidates, tokens.get(index), weight, index, sourceType);
            if (index + 1 < tokens.size()) {
                addCandidate(candidates, tokens.get(index) + " " + tokens.get(index + 1),
                        weight + 1.2, index, sourceType);
            }
        }
    }

    private List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        var tokens = new ArrayList<String>();
        for (String raw : TOKEN_SPLIT.split(text)) {
            var token = normalizeToken(raw);
            if (usefulToken(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String normalizeToken(String raw) {
        var token = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        while (token.length() > 2 && (token.endsWith("입니다") || token.endsWith("합니다"))) {
            token = token.substring(0, token.length() - 3);
        }
        if (token.length() > 2 && token.endsWith("입니다")) {
            token = token.substring(0, token.length() - 4);
        }
        if (token.length() > 2 && token.endsWith("습니다")) {
            token = token.substring(0, token.length() - 3);
        }
        if (token.length() > 2 && (token.endsWith("으로") || token.endsWith("에서") || token.endsWith("에도"))) {
            token = token.substring(0, token.length() - 2);
        }
        if (!protectedNounEnding(token) && token.length() > 2
                && (token.endsWith("을") || token.endsWith("를") || token.endsWith("은")
                || token.endsWith("는") || token.endsWith("이") || token.endsWith("가") || token.endsWith("과")
                || token.endsWith("와") || token.endsWith("의") || token.endsWith("도"))) {
            token = token.substring(0, token.length() - 1);
        }
        if (token.length() > 2 && token.endsWith("한")) {
            token = token.substring(0, token.length() - 1);
        }
        return token;
    }

    private boolean protectedNounEnding(String token) {
        return token.endsWith("마을")
                || token.endsWith("서울")
                || token.endsWith("전통마을");
    }

    private boolean usefulToken(String token) {
        return token != null
                && token.length() >= 2
                && token.length() <= DEFAULT_MAX_TAG_LENGTH
                && !lexicon.stopwords().contains(token);
    }

    private void addCandidate(
            Map<String, Candidate> candidates,
            String label,
            double weight,
            int position,
            ContentTagSourceType sourceType) {
        if (!usefulCandidate(label)) {
            return;
        }
        var score = weight + domainBoost(label) + positionBoost(position);
        candidates.compute(label, (key, existing) -> existing == null
                ? new Candidate(label, score, candidates.size(), sourceType)
                : existing.add(score, sourceType));
    }

    private boolean usefulCandidate(String label) {
        if (label == null || label.isBlank() || label.length() > DEFAULT_MAX_TAG_LENGTH) {
            return false;
        }
        if (lexicon.genericPhrases().contains(label)) {
            return false;
        }
        if (label.contains(" ") && List.of(label.split(" ")).stream().allMatch(lexicon.stopwords()::contains)) {
            return false;
        }
        return usefulToken(label) || label.contains(" ");
    }

    private double domainBoost(String label) {
        if (lexicon.domainPhrases().contains(label)) {
            return 12.0;
        }
        return lexicon.domainPhrases().stream().anyMatch(phrase -> phrase.contains(label) || label.contains(phrase))
                ? 0.5
                : 0.0;
    }

    private double positionBoost(int position) {
        return position < 8 ? (8 - position) * 0.08 : 0.0;
    }

    private record Candidate(
            String label,
            double score,
            int firstSeen,
            ContentTagSourceType source) {

        private Candidate add(double extraScore, ContentTagSourceType nextSource) {
            return new Candidate(label, score + extraScore, firstSeen, preferredSource(source, nextSource));
        }

        private ContentTagSourceType preferredSource(
                ContentTagSourceType current,
                ContentTagSourceType next) {
            if (current == ContentTagSourceType.HIGHLIGHT || next != ContentTagSourceType.HIGHLIGHT) {
                return current;
            }
            return next;
        }
    }
}
