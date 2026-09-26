package com.yrootlab.onmaru.stamp.ranking;

import java.security.SecureRandom;
import java.util.UUID;

public final class RandomStampRankingIdentityGenerator implements StampRankingIdentityGenerator {
    private static final String[] ADJECTIVES = {"고즈넉한", "다정한", "느긋한", "빛나는", "푸른"};
    private static final String[] NOUNS = {"여행자", "산책가", "탐방객", "길동무"};
    private static final String CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

    private final SecureRandom random = new SecureRandom();

    @Override
    public StampRankingIdentity generate() {
        var suffix = new StringBuilder(4);
        for (int i = 0; i < 4; i++) {
            suffix.append(CROCKFORD.charAt(random.nextInt(CROCKFORD.length())));
        }
        var nickname = ADJECTIVES[random.nextInt(ADJECTIVES.length)]
                + NOUNS[random.nextInt(NOUNS.length)] + "-" + suffix;
        return StampRankingIdentity.generated(UUID.randomUUID(), nickname);
    }
}
