package com.yrootlab.onmaru.kcontents.canonicalization;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/** Conservative identity key: spelling variants collapse, ambiguous seasons remain distinct. */
public final class WorkIdentity {
    private WorkIdentity() { }

    public static String normalize(String title) {
        if (title == null) return "";
        return Normalizer.normalize(title, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s]+", "")
                .trim();
    }

    public static boolean validType(String type) {
        return Set.of("DRAMA", "MOVIE", "VARIETY", "MUSIC_VIDEO").contains(type);
    }

    public static boolean conflicts(Integer leftYear, String leftSeason, Integer rightYear, String rightSeason) {
        return leftYear != null && rightYear != null && !leftYear.equals(rightYear)
                || leftSeason != null && rightSeason != null && !normalize(leftSeason).equals(normalize(rightSeason));
    }
}
