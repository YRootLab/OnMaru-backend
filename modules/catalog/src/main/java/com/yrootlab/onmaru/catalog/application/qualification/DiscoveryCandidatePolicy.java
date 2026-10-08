package com.yrootlab.onmaru.catalog.application.qualification;

import java.util.Set;
import java.util.regex.Pattern;

/** Versioned qualification for the new discovery revision. INCLUDE is a candidate, never publication approval. */
public final class DiscoveryCandidatePolicy {
    public static final String VERSION = "discovery-candidate-v1.0.0";
    private static final Set<String> STRONG = Set.of("HS010100", "HS010200", "HS010300", "HS010400", "HS010500", "HS010600", "EX010100", "AC030200", "VE040100", "VE040200");
    private static final Set<String> CONDITIONAL = Set.of("HS010700", "HS011100", "HS011200", "EX040200", "HS020100", "HS020300", "HS030100", "VE070100", "VE070200", "VE070300", "VE090100", "VE090400", "FD040400", "FD050200", "SH050100", "SH060100", "SH060200", "NA040700", "VE010100", "VE010900", "EX060100", "EX060300");
    private static final Pattern SIGNAL = Pattern.compile("한옥|고택|궁궐|고궁|민속|전통|향교|서원|국악|한복|한지|다도|차문화|문화재|왕릉|종택|초가|돌담|옛길|한과|떡만들기|전통주");
    private static final Pattern NEGATIVE = Pattern.compile("드론|케이블카|카지노|골프|워터파크|키즈카페|복합쇼핑몰");

    public Decision qualify(SourceRecord row, HumanDecision human, boolean duplicate) {
        Decision automatic = automatic(row, duplicate);
        if (human == null) return automatic;
        // A human's confirmed result is retained; a changed semantic result is queued for review.
        if (human.sourceFingerprint().equals(fingerprint(row))) {
            return new Decision(human.status(), human.role(), "HUMAN_CONFIRMED", VERSION, false);
        }
        if (automatic.status() == human.status() && automatic.role() == human.role()) {
            return new Decision(human.status(), human.role(), "HUMAN_CONFIRMED", VERSION, false);
        }
        return new Decision(Status.REVIEW, human.role(), "HUMAN_DECISION_CONFLICT", VERSION, false);
    }

    public Decision qualify(SourceRecord row) { return qualify(row, null, false); }

    private Decision automatic(SourceRecord row, boolean duplicate) {
        String id = value(row.field("contentid"));
        String title = value(row.field("title"));
        Double x = coordinate(row.field("mapx"));
        Double y = coordinate(row.field("mapy"));
        if (id.isEmpty() || title.isEmpty() || x == null || y == null || !Double.isFinite(x) || !Double.isFinite(y) || x < 124 || x > 132 || y < 33 || y > 39)
            return decision(Status.EXCLUDE, null, "SOURCE_INVALID");
        if (duplicate) return decision(Status.REVIEW, null, "DUPLICATE_IDENTITY");
        if (NEGATIVE.matcher(title).find()) return decision(Status.EXCLUDE, null, "UNRELATED_FACILITY");
        String code = value(row.field("lclsSystm3"));
        boolean signal = SIGNAL.matcher(title).find();
        if (STRONG.contains(code)) {
            Role role = code.startsWith("HS") || code.equals("AC030200") ? Role.CORE_TRADITIONAL_PLACE : Role.TRADITIONAL_EXPERIENCE;
            return decision(Status.INCLUDE, role, "STRONG_TAXONOMY_PENDING_DETAIL_GATE");
        }
        if (CONDITIONAL.contains(code))
            return signal ? decision(Status.INCLUDE, code.startsWith("HS") ? Role.CORE_TRADITIONAL_PLACE : Role.SURROUNDING_CULTURE, "CONDITIONAL_TITLE_SIGNAL_PENDING_DETAIL_GATE")
                    : decision(Status.REVIEW, null, "CONDITIONAL_NEEDS_CONTEXT");
        if (code.equals("FD050100") || code.equals("EX030100"))
            return signal ? decision(Status.REVIEW, null, "BROAD_CLASS_TITLE_SIGNAL_NEEDS_EVIDENCE")
                    : decision(Status.EXCLUDE, null, "BROAD_CLASS_NO_TRADITION_SIGNAL");
        if (code.startsWith("EV") || code.startsWith("LS") || code.startsWith("C01"))
            return decision(Status.EXCLUDE, null, "RESCUE_NO_PLACE_EVIDENCE");
        if (code.isEmpty() || !code.matches("[A-Z]{2}[0-9]{6}"))
            return decision(Status.REVIEW, null, "UNKNOWN_SOURCE_CODE");
        return signal ? decision(Status.REVIEW, null, "KEYWORD_RESCUE_NEEDS_EVIDENCE")
                : decision(Status.EXCLUDE, null, "RESCUE_NO_PLACE_EVIDENCE");
    }

    private static Decision decision(Status status, Role role, String reason) {
        return new Decision(status, role, reason, VERSION, false);
    }

    public static String fingerprint(SourceRecord row) {
        return value(row.field("title")) + "\u001f" + value(row.field("lclsSystm3")) + "\u001f" + value(row.field("mapx")) + "\u001f" + value(row.field("mapy"))
                + "\u001f" + value(row.field("overview")) + "\u001f" + value(row.field("detail"));
    }

    private static String value(String value) { return value == null ? "" : value.strip(); }
    private static Double coordinate(String value) {
        try { return Double.parseDouble(value); } catch (NullPointerException | NumberFormatException ignored) { return null; }
    }

    public enum Status { INCLUDE, REVIEW, EXCLUDE }
    public enum Role { CORE_TRADITIONAL_PLACE, TRADITIONAL_EXPERIENCE, SURROUNDING_CULTURE }
    public record Decision(Status status, Role role, String reasonCode, String policyVersion, boolean publicationApproved) { }
    public record HumanDecision(Status status, Role role, String sourceFingerprint) { }
}
