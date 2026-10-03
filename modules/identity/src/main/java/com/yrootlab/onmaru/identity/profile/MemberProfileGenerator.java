package com.yrootlab.onmaru.identity.profile;

import java.security.SecureRandom;
import java.util.function.IntUnaryOperator;

public final class MemberProfileGenerator {

    private static final String[] ADJECTIVES = {
            "고요한", "따뜻한", "느긋한", "정겨운", "포근한",
            "산뜻한", "다정한", "잔잔한", "맑은", "소박한"
    };
    private static final String[] NOUNS = {
            "마루", "기와", "골목", "달빛", "솔바람",
            "꽃길", "한지", "찻잔", "구름", "나들이"
    };

    private final IntUnaryOperator randomIndex;

    public MemberProfileGenerator() {
        var secureRandom = new SecureRandom();
        this.randomIndex = secureRandom::nextInt;
    }

    public MemberProfileGenerator(IntUnaryOperator randomIndex) {
        this.randomIndex = randomIndex;
    }

    public NewMemberProfile generate() {
        var displayName = "%s %s %04d".formatted(
                ADJECTIVES[next(ADJECTIVES.length)],
                NOUNS[next(NOUNS.length)],
                next(10_000));
        return new NewMemberProfile(
                displayName,
                MemberProfileCharacter.values()[next(MemberProfileCharacter.values().length)],
                MemberProfileBackground.values()[next(MemberProfileBackground.values().length)]);
    }

    private int next(int bound) {
        int value = randomIndex.applyAsInt(bound);
        if (value < 0 || value >= bound) {
            throw new IllegalStateException("random index must be within bound");
        }
        return value;
    }
}
