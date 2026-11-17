package com.yrootlab.onmaru.integration.ai.plan;

import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.AuthorityBounds;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.CanonicalField;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.ConfirmedContext;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Filters;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Limits;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.SafeQueryRequest;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.CurrentVersions;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExplorationContextReader;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExplorationSafeContext;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.SafeQueryFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Builds authority-bounded SafeQueryRequest payloads for AI plan-execute without pre-restricting AI corpus search
 * to a Spring pre-fetched candidate list.
 */
public final class DefaultSafeQueryFactory implements SafeQueryFactory {

    static final String SCHEMA_VERSION = "ai-safe-query.v1";
    static final String FALLBACK_SAFE_QUERY = "여행 코스 추천";
    static final int MAX_SAFE_QUERY_CODE_POINTS = 240;
    static final Limits DEFAULT_LIMITS = new Limits(20, 20, 30, 12, 5);

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern CONTROL_OR_FORMAT = Pattern.compile("[\\p{Cc}\\p{Cf}]");
    private static final Pattern EMAIL = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern RRN = Pattern.compile("(?<!\\d)\\d{6}[-\\s]?[1-4]\\d{6}(?!\\d)");
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(?:\\+82[-.\\s]?|0)(?:1[016789]|2|[3-6]\\d)[-.\\s]?\\d{3,4}[-.\\s]?\\d{4}(?!\\d)");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final int MAX_PINNED_OR_LAST = 5;
    private static final int MAX_EXCLUDED = 1_000;

    private final ExplorationContextReader contextReader;
    private final CurrentVersions currentVersions;
    private final AiPlanWireCodec wireCodec;

    public DefaultSafeQueryFactory(
            ExplorationContextReader contextReader,
            CurrentVersions currentVersions,
            AiPlanWireCodec wireCodec) {
        this.contextReader = contextReader;
        this.currentVersions = Objects.requireNonNull(currentVersions, "currentVersions must not be null");
        this.wireCodec = Objects.requireNonNull(wireCodec, "wireCodec must not be null");
    }

    @Override
    public SafeQueryRequest create(JourneyWorkerRequest request, CandidatePayload candidates) {
        Objects.requireNonNull(request, "request must not be null");
        String safeQuery = sanitizeQuery(request.queryText());
        ExplorationSafeContext context = contextReader == null
                ? ExplorationSafeContext.empty()
                : contextReader.read(request.explorationId());
        if (context == null) {
            context = ExplorationSafeContext.empty();
        }

        String regionCode = sanitizeIdentifier(request.regionCode());
        List<String> pinnedPlaceIds = sanitizeIdentifiers(context.pinnedPlaceIds(), MAX_PINNED_OR_LAST, Set.of());
        List<String> excludedPlaceIds = sanitizeIdentifiers(
                context.excludedPlaceIds(), MAX_EXCLUDED, Set.copyOf(pinnedPlaceIds));
        List<String> lastConfirmedPlaceIds = sanitizeIdentifiers(
                context.lastConfirmedPlaceIds(), MAX_PINNED_OR_LAST, Set.of());

        List<String> regionCodes = regionCode == null ? null : List.of(regionCode);
        String filterHash = computeFilterHash(regionCodes, pinnedPlaceIds, excludedPlaceIds);
        Filters filters = new Filters(
                filterHash,
                true,
                null,
                regionCodes,
                null,
                pinnedPlaceIds,
                excludedPlaceIds);

        return new SafeQueryRequest(
                SCHEMA_VERSION,
                request.runId(),
                safeQuery,
                new ConfirmedContext(regionCode, pinnedPlaceIds, excludedPlaceIds, lastConfirmedPlaceIds),
                new AuthorityBounds(filters, List.of(CanonicalField.values())),
                currentVersions.current(),
                DEFAULT_LIMITS);
    }

    private String computeFilterHash(
            List<String> regionCodes,
            List<String> pinnedPlaceIds,
            List<String> excludedPlaceIds) {
        var canonical = new LinkedHashMap<String, Object>();
        canonical.put("categoryCodes", null);
        canonical.put("excludedPlaceIds", excludedPlaceIds);
        canonical.put("pinnedPlaceIds", pinnedPlaceIds);
        canonical.put("placeIds", null);
        canonical.put("publicOnly", true);
        canonical.put("regionCodes", regionCodes);
        byte[] bytes = wireCodec.write(canonical);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static String sanitizeQuery(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_SAFE_QUERY;
        }
        String text = CONTROL_OR_FORMAT.matcher(raw).replaceAll(" ");
        text = EMAIL.matcher(text).replaceAll("[REDACTED]");
        text = RRN.matcher(text).replaceAll("[REDACTED]");
        text = PHONE.matcher(text).replaceAll("[REDACTED]");
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        if (text.isEmpty()) {
            return FALLBACK_SAFE_QUERY;
        }
        if (text.codePointCount(0, text.length()) > MAX_SAFE_QUERY_CODE_POINTS) {
            int endIndex = text.offsetByCodePoints(0, MAX_SAFE_QUERY_CODE_POINTS);
            text = text.substring(0, endIndex).trim();
            if (text.isEmpty()) {
                return FALLBACK_SAFE_QUERY;
            }
        }
        return text;
    }

    private static String sanitizeIdentifier(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return IDENTIFIER.matcher(trimmed).matches() ? trimmed : null;
    }

    private static List<String> sanitizeIdentifiers(List<String> rawValues, int limit, Set<String> forbidden) {
        if (rawValues == null || rawValues.isEmpty()) {
            return List.of();
        }
        var seen = new LinkedHashSet<String>();
        var result = new ArrayList<String>();
        for (String value : rawValues) {
            String clean = sanitizeIdentifier(value);
            if (clean == null || forbidden.contains(clean) || !seen.add(clean)) {
                continue;
            }
            result.add(clean);
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
