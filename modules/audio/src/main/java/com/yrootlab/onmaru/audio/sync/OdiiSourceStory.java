package com.yrootlab.onmaru.audio.sync;

public record OdiiSourceStory(
        String tid,
        String tlid,
        String stid,
        String stlid,
        String title,
        String audioTitle,
        String script,
        String audioUrl,
        String imageUrl,
        String playTime,
        String mapX,
        String mapY,
        String langCode,
        String createdTime,
        String modifiedTime
) {
}
