package com.yrootlab.onmaru.web.screenhanok;

import com.yrootlab.onmaru.catalog.screenhanok.ScreenHanokEntry;

import java.util.List;

record ScreenHanokItemResponse(
        String placeId,
        String name,
        String region,
        String imageUrl,
        String mediaType,
        String categoryLabel,
        String categoryIcon,
        String workTitle,
        String subtitle,
        List<String> tags,
        String sourceUrl,
        String sourceTitle,
        boolean savedByMe) {

    static ScreenHanokItemResponse from(ScreenHanokEntry entry) {
        return new ScreenHanokItemResponse(
                entry.placeId(),
                entry.name(),
                entry.regionName(),
                entry.imageUrl(),
                entry.mediaType().name(),
                categoryLabel(entry),
                categoryIcon(entry),
                entry.workTitle(),
                entry.subtitle(),
                entry.tags(),
                entry.sourceUrl(),
                entry.sourceTitle(),
                entry.savedByMe());
    }

    private static String categoryLabel(ScreenHanokEntry entry) {
        return switch (entry.mediaType()) {
            case K_DRAMA -> "K-드라마 · 사극 로케이션";
            case CINEMA -> "한국 영화 로케이션";
            case KPOP -> "K-POP 뮤비 · 화보 로케이션";
        };
    }

    private static String categoryIcon(ScreenHanokEntry entry) {
        return switch (entry.mediaType()) {
            case K_DRAMA -> "🎬";
            case CINEMA -> "🎥";
            case KPOP -> "🎵";
        };
    }
}
