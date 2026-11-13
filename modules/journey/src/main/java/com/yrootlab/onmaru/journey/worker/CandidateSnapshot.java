package com.yrootlab.onmaru.journey.worker;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Identifies the exact candidate input sent to AI so an evaluation run can be reproduced and compared.
 * The hash covers the policy version, the ordered candidate refs, the region code and the dataset revision.
 * The visitor's query text is deliberately excluded.
 */
public final class CandidateSnapshot {

    /** The only candidate policy the backend sends today. Requests omit the field until AI accepts it. */
    public static final String POLICY_V1 = "v1";

    /** Length of the hex identifier written to logs and evaluation records. */
    public static final int ID_LENGTH = 32;

    private CandidateSnapshot() {
    }

    public static String id(String candidatePolicy, String regionCode, String datasetRevision, List<String> orderedRefs) {
        var canonical = new StringBuilder()
                .append("policy=").append(nullToEmpty(candidatePolicy)).append('\n')
                .append("region=").append(nullToEmpty(regionCode)).append('\n')
                .append("dataset=").append(nullToEmpty(datasetRevision)).append('\n');
        for (String ref : orderedRefs) {
            canonical.append("ref=").append(ref).append('\n');
        }
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, ID_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
