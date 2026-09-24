package com.yrootlab.onmaru.audio.query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 이번 주(또는 전체 기간) 인기 오디오 스토리 랭킹 조회 조건.
 *
 * @param language      해설 언어 (ko-KR 등)
 * @param category      선택 카테고리 필터
 * @param limit         1~20
 * @param sinceInclusive 인기 집계 시작 시각 (전체 기간이면 Instant.EPOCH)
 * @param memberId      저장 여부 표시용 회원 (비인증이면 empty)
 */
public record OdiiPopularSoundsQuery(
        String language,
        String category,
        int limit,
        Instant sinceInclusive,
        Optional<UUID> memberId) {
}
