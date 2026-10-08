package com.yrootlab.onmaru.kcontents.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.kcontents.canonicalization.WorkIdentity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Server-side fact gate. Source text is data, never an instruction or a confidence score. */
public final class ExtractionValidator {
    public static final String SCHEMA_VERSION = "kcontents-extraction-v1";
    public static final String PROMPT_VERSION = "kcontents-evidence-v1";
    public static final String RULE_VERSION = "kcontents-validation-v1";
    private static final Set<String> TRUSTED_NEWS_HOSTS = Set.of("yna.co.kr", "newsis.com", "koreatimes.co.kr", "mk.co.kr", "hankyung.com");
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Place(UUID id, String name, String region) { }
    public record Evidence(UUID id, String url, String title, String publisher, String excerpt,
                           boolean official, boolean verifiedOrigin) {
        public Evidence(UUID id, String url, String title, String publisher, String excerpt, boolean official) {
            this(id, url, title, publisher, excerpt, official, false);
        }
    }
    public record Claim(UUID evidenceId, String quote) { }
    public record Tag(String rawLabel, String scope, String groupHint, UUID evidenceId, String quote, boolean valid, String reason) { }
    public record Summary(String scope, String text, UUID evidenceId, String quote, boolean valid, String reason) { }
    public record Candidate(String title, String normalizedTitle, String workType, Integer releaseYear, String seasonKey,
                            List<String> aliases, String status, String reason, double confidence,
                            List<Claim> claims, List<Tag> tags, List<Summary> summaries) { }
    public record Report(String status, List<Candidate> candidates, String fingerprint) { }

    public Report validate(UUID placeId, String sourceFingerprint, String schemaVersion, String promptVersion,
                           String modelVersion, String resultStatus, String resultJson, Place place,
                           List<Evidence> evidence) {
        if (place == null || !placeId.equals(place.id())) throw new IllegalArgumentException("PLACE_MISMATCH");
        Map<UUID, Evidence> byId = new HashMap<>();
        evidence.forEach(item -> byId.put(item.id(), item));
        String fingerprint = fingerprint(placeId, sourceFingerprint, schemaVersion, promptVersion, modelVersion, evidence);
        if (!SCHEMA_VERSION.equals(schemaVersion) || !PROMPT_VERSION.equals(promptVersion))
            return new Report("STALE", List.of(), fingerprint);
        try {
            JsonNode root = JSON.readTree(resultJson);
            if (root == null || !root.isObject() || !root.path("resultStatus").asText("").equals(resultStatus)
                    || !root.path("candidates").isArray()) throw new IllegalArgumentException("SCHEMA_INVALID");
            if (!onlyFields(root, "resultStatus", "candidates", "ambiguityReason", "nextSearchAt")) throw new IllegalArgumentException("SCHEMA_INVALID");
            if (!Set.of("MATCH", "NO_MATCH", "UNCERTAIN").contains(resultStatus)) throw new IllegalArgumentException("SCHEMA_INVALID");
            if ("NO_MATCH".equals(resultStatus) && root.path("candidates").size() != 0) throw new IllegalArgumentException("SCHEMA_INVALID");
            if (root.has("nextSearchAt")) {
                if (!"NO_MATCH".equals(resultStatus) || !root.path("nextSearchAt").isTextual())
                    throw new IllegalArgumentException("SCHEMA_INVALID");
                try { java.time.Instant.parse(root.path("nextSearchAt").asText()); }
                catch (Exception invalid) { throw new IllegalArgumentException("SCHEMA_INVALID", invalid); }
            }
            List<Candidate> candidates = new ArrayList<>();
            for (JsonNode node : root.path("candidates")) {
                requireCandidateShape(node);
                Candidate checked = validateCandidate(node, place, byId);
                if ("UNCERTAIN".equals(resultStatus) && "AUTO_VERIFIED".equals(checked.status()))
                    checked = new Candidate(checked.title(), checked.normalizedTitle(), checked.workType(),
                            checked.releaseYear(), checked.seasonKey(), checked.aliases(), "REVIEW_REQUIRED",
                            "WORKER_UNCERTAIN", checked.confidence(), checked.claims(), checked.tags(), checked.summaries());
                candidates.add(checked);
            }
            if ("MATCH".equals(resultStatus) && candidates.isEmpty()) throw new IllegalArgumentException("SCHEMA_INVALID");
            String overall = "NO_MATCH".equals(resultStatus) ? "NO_MATCH"
                    : "UNCERTAIN".equals(resultStatus) && candidates.isEmpty() ? "REVIEW_REQUIRED"
                    : candidates.stream().anyMatch(c -> c.status().equals("REVIEW_REQUIRED")) ? "REVIEW_REQUIRED"
                    : candidates.stream().anyMatch(c -> c.status().equals("AUTO_VERIFIED")) ? "AUTO_VERIFIED"
                    : candidates.isEmpty() ? "REJECTED" : "REJECTED";
            return new Report(overall, List.copyOf(candidates), fingerprint);
        } catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalArgumentException("SCHEMA_INVALID", exception); }
    }

    private Candidate validateCandidate(JsonNode node, Place place, Map<UUID, Evidence> evidence) {
        String title = text(node, "title"), type = text(node, "workType");
        String normalized = WorkIdentity.normalize(title);
        Integer year = node.path("releaseYear").isIntegralNumber() ? node.path("releaseYear").asInt() : null;
        String season = optional(node, "seasonKey");
        List<String> aliases = new ArrayList<>();
        if (node.path("aliases").isArray()) node.path("aliases").forEach(alias -> {
            if (alias.isTextual() && !WorkIdentity.normalize(alias.asText()).isEmpty()) aliases.add(alias.asText());
        });
        List<Claim> claims = new ArrayList<>();
        List<Tag> tags = new ArrayList<>();
        List<Summary> summaries = new ArrayList<>();
        String reason = null;
        if (normalized.isEmpty() || !WorkIdentity.validType(type) || year != null && (year < 1895 || year > 2200)) reason = "WORK_IDENTITY_INVALID";
        if (!place.id().toString().equals(text(node, "placeId"))) reason = "PLACE_MISMATCH";
        if (place.name() == null || WorkIdentity.normalize(place.name()).isEmpty()
                || !WorkIdentity.normalize(place.name()).equals(WorkIdentity.normalize(text(node, "placeName")))) reason = "PLACE_NAME_MISMATCH";
        if (place.region() == null || WorkIdentity.normalize(place.region()).isEmpty()
                || !WorkIdentity.normalize(place.region()).equals(WorkIdentity.normalize(text(node, "region")))) reason = "REGION_MISMATCH";
        if (!"FILMING_LOCATION".equals(text(node, "relationType"))) reason = "RELATION_TYPE_INVALID";
        if (!node.path("evidence").isArray() || node.path("evidence").isEmpty()) reason = "EVIDENCE_MISSING";
        Set<String> independent = new HashSet<>();
        boolean official = false;
        boolean unsupported = false;
        for (JsonNode claim : node.path("evidence")) {
            UUID id = uuid(text(claim, "evidenceId"));
            String quote = text(claim, "quote");
            Evidence source = evidence.get(id);
            if (source == null || !supported(source, quote, title, place.name(), place.region(), type)) {
                unsupported = true;
                continue;
            }
            claims.add(new Claim(id, quote));
            official |= source.official() && source.verifiedOrigin();
            if (source.verifiedOrigin() && (source.official() || trustedNews(source.url())))
                independent.add(authority(source.url(), source.publisher()));
        }
        if (unsupported || claims.isEmpty()) reason = "EVIDENCE_UNSUPPORTED";
        if ("MUSIC_VIDEO".equals(type) && claims.stream().noneMatch(c -> mvLanguage(c.quote()))) reason = "MV_NOT_PROVEN";
        Integer proposedYear = year;
        String proposedSeason = season;
        if (year != null && claims.stream().noneMatch(c -> c.quote().contains(Integer.toString(proposedYear)))) year = null;
        if (season != null && claims.stream().noneMatch(c -> containsNormalized(c.quote(), proposedSeason))) season = null;
        for (JsonNode tag : node.path("tags")) tags.add(validateTag(tag, evidence));
        for (JsonNode summary : node.path("summaries")) summaries.add(validateSummary(summary, evidence, place, title));
        double confidence = reason != null ? 0 : official ? 0.95 : independent.size() >= 2 ? 0.91 : 0.72;
        String status = reason != null ? "REJECTED" : confidence >= 0.90 ? "AUTO_VERIFIED" : "REVIEW_REQUIRED";
        if (reason == null && status.equals("REVIEW_REQUIRED")) reason = "INDEPENDENT_SOURCE_REQUIRED";
        return new Candidate(title, normalized, type, year, season, List.copyOf(aliases), status, reason,
                confidence, List.copyOf(claims), List.copyOf(tags), List.copyOf(summaries));
    }

    private static void requireCandidateShape(JsonNode node) {
        if (!node.isObject() || !onlyFields(node, "title", "workType", "releaseYear", "seasonKey", "aliases",
                "placeId", "placeName", "region", "relationType", "evidence", "tags", "summaries")
                || !hasText(node, "title") || !hasText(node, "workType") || !hasText(node, "placeId")
                || !hasText(node, "placeName") || !hasText(node, "region") || !hasText(node, "relationType")
                || !node.path("evidence").isArray() || node.path("evidence").isEmpty()
                || !node.path("tags").isArray() || !node.path("summaries").isArray()
                || node.has("aliases") && !node.path("aliases").isArray()
                || node.has("releaseYear") && !node.path("releaseYear").isNull() && !node.path("releaseYear").isIntegralNumber()
                || node.has("seasonKey") && !node.path("seasonKey").isNull() && !node.path("seasonKey").isTextual())
            throw new IllegalArgumentException("SCHEMA_INVALID");
        for (JsonNode claim : node.path("evidence"))
            if (!claim.isObject() || !onlyFields(claim, "evidenceId", "quote")
                    || !hasText(claim, "evidenceId") || !hasText(claim, "quote") || uuid(text(claim, "evidenceId")) == null)
                throw new IllegalArgumentException("SCHEMA_INVALID");
        for (JsonNode tag : node.path("tags"))
            if (!tag.isObject() || !onlyFields(tag, "rawLabel", "scope", "groupHint", "evidenceId", "quote")
                    || !hasText(tag, "rawLabel") || !hasText(tag, "scope") || !hasText(tag, "groupHint")
                    || !hasText(tag, "evidenceId") || !hasText(tag, "quote") || uuid(text(tag, "evidenceId")) == null)
                throw new IllegalArgumentException("SCHEMA_INVALID");
        for (JsonNode summary : node.path("summaries"))
            if (!summary.isObject() || !onlyFields(summary, "scope", "text", "evidenceId", "quote")
                    || !hasText(summary, "scope") || !hasText(summary, "text")
                    || !hasText(summary, "evidenceId") || !hasText(summary, "quote")
                    || uuid(text(summary, "evidenceId")) == null)
                throw new IllegalArgumentException("SCHEMA_INVALID");
    }

    private static boolean onlyFields(JsonNode node, String... permitted) {
        Set<String> allowed = Set.of(permitted);
        var names = node.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) return false;
        return true;
    }
    private static boolean hasText(JsonNode node, String name) {
        return node.path(name).isTextual() && !node.path(name).asText().isBlank();
    }

    private Tag validateTag(JsonNode node, Map<UUID, Evidence> sources) {
        String label = text(node, "rawLabel"), scope = text(node, "scope"), group = text(node, "groupHint");
        UUID id = uuid(text(node, "evidenceId")); String quote = text(node, "quote");
        boolean shape = !WorkIdentity.normalize(label).isEmpty() &&
                (scope.equals("WORK_TAG") && Set.of("WORK_GENRE", "WORK_THEME").contains(group)
                        || scope.equals("RELATION_TAG") && group.equals("RELATION_CONTEXT"));
        boolean valid = shape && sourceContains(sources.get(id), quote) && containsNormalized(quote, label)
                && !negative(quote) && !instruction(quote);
        return new Tag(label, scope, group, id, quote, valid, valid ? null : "TAG_EVIDENCE_OR_SCOPE_INVALID");
    }

    private Summary validateSummary(JsonNode node, Map<UUID, Evidence> sources, Place place, String title) {
        String scope = text(node, "scope"), body = text(node, "text"), quote = text(node, "quote");
        UUID id = uuid(text(node, "evidenceId"));
        boolean valid = Set.of("WORK", "RELATION").contains(scope) && !body.isBlank() && body.length() <= 500
                && sourceContains(sources.get(id), quote) && !instruction(body)
                && containsNormalized(quote, body)
                && (scope.equals("WORK") ? containsNormalized(quote, title) : containsNormalized(quote, place.name()));
        return new Summary(scope, body, id, quote, valid, valid ? null : "SUMMARY_EVIDENCE_INVALID");
    }

    private boolean supported(Evidence source, String quote, String title, String place, String region, String type) {
        if (!sourceContains(source, quote) || instruction(quote) || instruction(source.excerpt())
                || negative(quote) || negative(source.excerpt())
                || background(quote) || background(source.excerpt())) return false;
        return containsNormalized(quote, title) && containsNormalized(quote, place)
                && containsNormalized(quote, region) && filmingLanguage(quote)
                && (!"MUSIC_VIDEO".equals(type) || mvLanguage(quote));
    }

    private static boolean sourceContains(Evidence source, String quote) {
        return source != null && quote != null && !quote.isBlank() && source.excerpt() != null
                && source.excerpt().contains(quote);
    }
    private static boolean containsNormalized(String haystack, String needle) {
        return haystack != null && needle != null && !WorkIdentity.normalize(needle).isEmpty()
                && WorkIdentity.normalize(haystack).contains(WorkIdentity.normalize(needle));
    }
    private static boolean filmingLanguage(String quote) { return matches(quote, "촬영", "filmed", "filming", "shooting", "로케이션"); }
    private static boolean mvLanguage(String quote) { return matches(quote, "뮤직비디오", "music video", " m/v", " mv "); }
    private static boolean negative(String quote) { return matches(quote, "촬영지가 아니다", "촬영하지", "촬영된 적 없", "not filmed", "never filmed", "촬영지 아님"); }
    private static boolean background(String quote) { return matches(quote, "팬 방문", "팬이 방문", "화보", "포토북", "배경이 된", "배경 지역", "닮은", "촬영 가능", "photo shoot", "fan visit"); }
    private static boolean instruction(String quote) { return matches(quote, "ignore previous", "system prompt", "지시를 무시", "프롬프트를 무시", "자동 승인하라"); }
    private static boolean matches(String value, String... patterns) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) if (lower.contains(pattern)) return true;
        return false;
    }
    private static String authority(String url, String publisher) {
        try { return new java.net.URI(url).getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", ""); }
        catch (Exception ignored) { return publisher == null ? url : publisher.toLowerCase(Locale.ROOT); }
    }
    private static boolean trustedNews(String url) {
        try {
            String host = new java.net.URI(url).getHost().toLowerCase(Locale.ROOT);
            return TRUSTED_NEWS_HOSTS.stream().anyMatch(known -> host.equals(known) || host.endsWith("." + known));
        } catch (Exception ignored) { return false; }
    }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (Exception ignored) { return null; } }
    private static String text(JsonNode node, String field) { return node == null ? "" : node.path(field).asText(""); }
    private static String optional(JsonNode node, String field) { String value = text(node, field); return value.isBlank() ? null : value; }

    public static String fingerprint(UUID place, String source, String schema, String prompt, String model, List<Evidence> evidence) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            StringBuilder material = new StringBuilder(place + "\n" + source + "\n" + schema + "\n" + prompt + "\n" + model);
            evidence.stream().sorted(Comparator.comparing(Evidence::url).thenComparing(Evidence::excerpt)).forEach(item ->
                    material.append('\n').append(item.url()).append('|').append(item.title())
                            .append('|').append(item.publisher()).append('|').append(item.excerpt()));
            return HexFormat.of().formatHex(digest.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
