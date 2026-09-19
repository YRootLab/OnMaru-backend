package com.yrootlab.onmaru.tourism.audio.client;

public record OdiiSourceItem(
        String operation,
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
