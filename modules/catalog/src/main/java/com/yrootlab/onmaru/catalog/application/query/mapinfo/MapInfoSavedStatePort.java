package com.yrootlab.onmaru.catalog.application.query.mapinfo;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 사용자별 저장 상태를 public map read projection과 분리해 batch로 조회한다. */
@FunctionalInterface
public interface MapInfoSavedStatePort {

    Set<String> savedPlaceIds(Optional<UUID> memberId, Collection<String> placeIds);

    static MapInfoSavedStatePort noOp() {
        return (memberId, placeIds) -> Set.of();
    }
}
