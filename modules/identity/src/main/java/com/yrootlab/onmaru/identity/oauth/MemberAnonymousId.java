package com.yrootlab.onmaru.identity.oauth;

import java.util.UUID;

public final class MemberAnonymousId {

    private static final char[] CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private MemberAnonymousId() {
    }

    public static String from(UUID memberId) {
        var encoded = new StringBuilder(17);
        for (int group = 0; group < 17; group++) {
            int value = 0;
            for (int offset = 0; offset < 5; offset++) {
                int bitIndex = group * 5 + offset;
                long source = bitIndex < 64
                        ? memberId.getMostSignificantBits()
                        : memberId.getLeastSignificantBits();
                int sourceIndex = bitIndex < 64 ? bitIndex : bitIndex - 64;
                value = value << 1 | (int) ((source >>> (63 - sourceIndex)) & 1L);
            }
            encoded.append(CROCKFORD_BASE32[value]);
        }
        return "익명-" + encoded;
    }
}
