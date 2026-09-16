package com.yrootlab.onmaru.audio.sync;

import java.math.BigDecimal;
import java.util.Objects;

public record AudioSubtitleLine(
        int position,
        BigDecimal startSeconds,
        String text,
        SubtitleTimingMode timingMode) {

    public AudioSubtitleLine {
        if (position < 0) {
            throw new IllegalArgumentException("position must be non-negative");
        }
        if (startSeconds != null && startSeconds.signum() < 0) {
            throw new IllegalArgumentException("startSeconds must be non-negative");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
        Objects.requireNonNull(timingMode, "timingMode");
    }
}
