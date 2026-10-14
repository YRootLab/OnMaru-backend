package com.yrootlab.onmaru.scheduling.catalog.detail;

import java.util.List;

/**
 * 상세 수집의 일일 상한과 카테고리 우선순위다.
 * 상한 800은 TourAPI 기능별 일일 1,000건에서 사이트 실시간 조회 몫 200건을 남기는 값이다.
 */
public final class DetailCollectionPolicy {

    public static final int MAX_DAILY_CAP = 800;

    /**
     * 우선순위는 저장소에서 SQL로 판정한다(JdbcDetailCollectionStore).
     * 1 한옥 · 2 한옥숙박 · 3 한옥카페 · 4 한옥체험 · 5 궁궐(역사유적 중 lclsSystm3 HS010100) · 6 전통시장.
     * 일반 역사유적과 맛집은 대상이 아니다.
     */
    public static final String PALACE_LCLS_SYSTM3 = "HS010100";

    private final int dailyCap;

    public DetailCollectionPolicy(int dailyCap) {
        if (dailyCap <= 0 || dailyCap > MAX_DAILY_CAP) {
            throw new IllegalArgumentException("dailyCap must be between 1 and " + MAX_DAILY_CAP);
        }
        this.dailyCap = dailyCap;
    }

    public int dailyCap() {
        return dailyCap;
    }

    public int remaining(int usedToday) {
        return Math.max(0, dailyCap - Math.max(0, usedToday));
    }
}
