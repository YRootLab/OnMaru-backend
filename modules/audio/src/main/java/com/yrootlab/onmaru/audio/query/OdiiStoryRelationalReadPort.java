package com.yrootlab.onmaru.audio.query;

import java.time.Instant;

/**
 * PostgreSQL이 활성 revision 필터, 공개 ID 탐색, 정렬과 LIMIT을 수행하는 ODII read port.
 */
public interface OdiiStoryRelationalReadPort {

    OdiiStoryReadPage list(
            String language,
            int limit,
            Instant cursorPublishedAt,
            String cursorStoryId);

    OdiiStoryReadPage listTheme(
            String language,
            OdiiStoryTheme theme,
            int limit,
            Instant cursorPublishedAt,
            String cursorStoryId);

    OdiiStoryReadSelection detail(String storyId, String language);

    OdiiStoryReadSelection search(String keyword, String language, int limit, boolean ranked);

    OdiiStoryReadSelection nearby(
            double latitude,
            double longitude,
            double radiusMeters,
            String language,
            int limit);

    OdiiPopularReadSelection popular(
            String language,
            String category,
            Instant sinceInclusive,
            int limit);
}
