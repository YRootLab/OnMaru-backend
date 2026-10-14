package com.yrootlab.onmaru.scheduling.catalog.detail;

import java.time.Instant;
import java.util.List;

public interface DetailCollectionStore {

    /** 같은 날 상세 호출을 시도한 횟수. 장소 1건의 시도 1회가 한도 1건을 소모한다. */
    int attemptsToday();

    /** 아직 OK가 아닌 대상 장소를 우선순위 순서로 최대 limit건 돌려준다. 실패 건은 미시도 건 뒤에 온다. */
    List<DetailCollectionTarget> nextTargets(int limit);

    void save(DetailCollectionTarget target, DetailFetchResult result, Instant attemptedAt);

    /** 블루/그린 배포 중 두 인스턴스가 동시에 돌지 않도록 잠근다. 잠그지 못하면 null. 닫으면 잠금을 푼다. */
    AutoCloseable tryLock();
}
