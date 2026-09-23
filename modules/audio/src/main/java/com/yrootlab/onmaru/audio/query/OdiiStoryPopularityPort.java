package com.yrootlab.onmaru.audio.query;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;

/**
 * 오디오 스토리의 인기 신호(재생 수·저장 수) 조회 포트.
 *
 * <p>운영에서는 JDBC 구현이 {@code audio_story_play_events}와
 * {@code journey_saved_odii_stories}를 집계한다. 로컬/테스트는 인메모리 구현을 사용한다.
 * 저장 수가 이 포트로 조회되므로 JDBC 구현은 저장 훅(recordSave)을 무시해도 된다.</p>
 */
public interface OdiiStoryPopularityPort {

    void recordPlay(String storyId, Instant occurredAt);

    void recordSave(String storyId, Instant occurredAt);

    Map<String, Long> playCounts(Collection<String> storyIds, Instant sinceInclusive);

    Map<String, Long> saveCounts(Collection<String> storyIds, Instant sinceInclusive);
}
