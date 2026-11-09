package com.yrootlab.onmaru.persistence.catalog.journey;

import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;

import javax.sql.DataSource;
import java.util.List;

/** Supplies the journey candidate set from the active catalog revision, scoped to the request's region. */
public final class JdbcJourneyCandidateProvider implements JourneyCandidateProvider {

    /** AI proposal input bound (candidateCount <= 12). */
    static final int MAX_CANDIDATES = 12;

    private final DataSource dataSource;

    public JdbcJourneyCandidateProvider(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public CandidatePayload retrieve(JourneyWorkerRequest request) {
        var places = ApprovedPlaceReader.read(dataSource, request.regionCode(), null, MAX_CANDIDATES);
        List<JourneyCandidate> candidates = places.stream()
                .map(place -> new JourneyCandidate(place.placeId()))
                .toList();
        return new CandidatePayload(request.datasetRevision(), candidates);
    }
}
