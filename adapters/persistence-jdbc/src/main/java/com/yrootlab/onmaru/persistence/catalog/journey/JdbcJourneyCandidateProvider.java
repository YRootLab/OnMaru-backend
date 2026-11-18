package com.yrootlab.onmaru.persistence.catalog.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.ExplorationContextReader;
import com.yrootlab.onmaru.persistence.journey.exploration.JdbcExplorationContextReader;

import javax.sql.DataSource;
import java.util.List;
import java.util.Objects;

/** Supplies the journey candidate set from the active catalog revision, scoped to the request's region. */
public final class JdbcJourneyCandidateProvider implements JourneyCandidateProvider {

    /** AI proposal input bound (candidateCount <= 12). */
    static final int MAX_CANDIDATES = 12;

    private final DataSource dataSource;
    private final ExplorationContextReader contextReader;

    public JdbcJourneyCandidateProvider(DataSource dataSource) {
        this(dataSource, new JdbcExplorationContextReader(dataSource, new ObjectMapper()));
    }

    public JdbcJourneyCandidateProvider(DataSource dataSource, ExplorationContextReader contextReader) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.contextReader = Objects.requireNonNull(contextReader, "contextReader must not be null");
    }

    @Override
    public CandidatePayload retrieve(JourneyWorkerRequest request) {
        List<String> excludedPlaceIds = request.explorationId() != null
                ? contextReader.read(request.explorationId()).excludedPlaceIds()
                : List.of();
        var places = ApprovedPlaceReader.read(
                dataSource, request.regionCode(), null, null, excludedPlaceIds, MAX_CANDIDATES);
        List<JourneyCandidate> candidates = places.stream()
                .map(place -> new JourneyCandidate(place.placeId()))
                .toList();
        return new CandidatePayload(request.datasetRevision(), candidates);
    }
}
