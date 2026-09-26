package com.yrootlab.onmaru.stamp.ranking;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record StampRankingIdentity(
        UUID publicId,
        String publicNickname,
        String nicknameNormalized,
        StampRankingNicknameType nicknameType) {

    public StampRankingIdentity {
        Objects.requireNonNull(publicId, "publicId");
        Objects.requireNonNull(publicNickname, "publicNickname");
        Objects.requireNonNull(nicknameNormalized, "nicknameNormalized");
        Objects.requireNonNull(nicknameType, "nicknameType");
    }

    public static StampRankingIdentity generated(UUID publicId, String nickname) {
        if (nickname == null) {
            throw new StampRankingInputInvalidException("publicNickname");
        }
        var display = Normalizer.normalize(nickname.strip(), Normalizer.Form.NFKC).strip();
        int length = display.codePointCount(0, display.length());
        if (length < 2 || length > 20) {
            throw new StampRankingInputInvalidException("publicNickname");
        }
        return new StampRankingIdentity(
                publicId, display, display.toLowerCase(Locale.ROOT),
                StampRankingNicknameType.GENERATED);
    }
}
