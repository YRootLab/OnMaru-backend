package com.yrootlab.onmaru.kcontents.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The public reader delegates the evidence gate to the database view. */
public interface KContentRelationRepository {
    record PublicRelation(UUID id, UUID placeId, UUID workId, String relationType, Instant verifiedAt) {}

    List<PublicRelation> findPublicByPlace(UUID placeId);
    List<PublicRelation> findPublicByWork(UUID workId);
}
