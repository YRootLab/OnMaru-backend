package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;

import javax.sql.DataSource;
import java.util.List;

/**
 * Supplies the journey candidate set from the active catalog revision. Candidates are drawn nationwide and ranked by
 * relevance to the question; the requested region only adds a bonus, it never excludes a place.
 */
public final class JdbcJourneyCandidateProvider implements JourneyCandidateProvider {

    /** AI proposal input bound (candidateCount <= 12). */
    static final int MAX_CANDIDATES = 12;

    /** Nationwide pool size handed to the relevance ranker before the top {@link #MAX_CANDIDATES} are kept. */
    static final int POOL_SIZE = 400;

    private final DataSource dataSource;

    public JdbcJourneyCandidateProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public CandidatePayload retrieve(JourneyWorkerRequest request) {
        var tokens = JourneyRelevanceRanker.tokens(request.queryText());
        var pool = ApprovedPlaceReader.readPool(dataSource, tokens, POOL_SIZE);
        var places = JourneyRelevanceRanker.rank(tokens, request.regionCode(), pool, MAX_CANDIDATES);
        List<JourneyCandidate> candidates = places.stream()
                .map(place -> new JourneyCandidate(place.placeId()))
                .toList();
        return new CandidatePayload(request.datasetRevision(), candidates);
    }
}
