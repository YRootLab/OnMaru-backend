package com.yrootlab.onmaru.scheduling.audio;

import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiRevisionSyncService;
import com.yrootlab.onmaru.audio.sync.OdiiSyncCommand;
import com.yrootlab.onmaru.audio.sync.OdiiSyncObserver;
import com.yrootlab.onmaru.audio.sync.OdiiSyncStatus;
import com.yrootlab.onmaru.catalog.application.sync.SyncRunLease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
@Profile("!staging")
public class OdiiSyncSchedulingAdapter {

    private static final Logger LOGGER = LoggerFactory.getLogger(OdiiSyncSchedulingAdapter.class);
    private static final String OWNER_TOKEN = "odii-sync-scheduler";

    private final ObjectProvider<OdiiRevisionSyncService> syncServiceProvider;
    private final ObjectProvider<AudioRevisionStore> revisionStoreProvider;
    private final ObjectProvider<DataSource> dataSourceProvider;
    private final String dataset;
    private final List<String> languages;

    public OdiiSyncSchedulingAdapter(
            ObjectProvider<OdiiRevisionSyncService> syncServiceProvider,
            ObjectProvider<AudioRevisionStore> revisionStoreProvider,
            ObjectProvider<DataSource> dataSourceProvider,
            @Value("${onmaru.audio.dataset:odii-audio}") String dataset,
            @Value("${onmaru.odii.sync.languages:ko}") List<String> languages
    ) {
        this.syncServiceProvider = syncServiceProvider;
        this.revisionStoreProvider = revisionStoreProvider;
        this.dataSourceProvider = dataSourceProvider;
        this.dataset = dataset;
        this.languages = languages == null || languages.isEmpty() ? List.of("ko") : List.copyOf(languages);
    }

    @Scheduled(cron = "${onmaru.odii.sync.cron:0 0 3 */3 * *}", zone = "Asia/Seoul")
    public void scheduledSync() {
        LOGGER.info("triggering scheduled odii sync run");
        runSync("scheduled-cron");
    }

    /**
     * Recover an empty audio dataset immediately after a fresh deployment.
     * The normal cron remains the periodic refresh mechanism, while this
     * startup hook prevents the public Odii endpoints from staying at 503
     * until the next scheduled window.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialSync() {
        LOGGER.info("triggering initial odii sync run");
        runSync("application-ready");
    }

    public synchronized void runSync(String triggerSource) {
        UUID runId = UUID.randomUUID();
        Instant startedAt = Instant.now();
        String trigger = switch (triggerSource == null ? "" : triggerSource) {
            case "application-ready", "scheduled-cron" -> triggerSource;
            default -> "manual";
        };
        var outcome = new RunOutcome();
        OdiiSyncRunStore history = null;
        LOGGER.info("ODII_SYNC_STARTED runId={} trigger={} dataset={}", runId, trigger, dataset);
        try {
            // Resolve the DB first, so missing/failed application beans can still leave history.
            var dataSource = dataSourceProvider.getIfAvailable();
            if (dataSource != null) {
                history = new JdbcOdiiSyncRunStore(dataSource);
                saveHistory(history, new OdiiSyncRunStore.Run(runId, dataset, trigger, startedAt,
                        null, "STARTED", null, null, null, null, 0, 0));
            }
            var syncService = syncServiceProvider.getIfAvailable();
            var revisionStore = revisionStoreProvider.getIfAvailable();
            if (syncService == null || revisionStore == null || dataSource == null) {
                outcome.status = "SKIPPED";
                outcome.code = "MISSING_COMPONENT";
                LOGGER.warn("ODII_SYNC_COMPONENTS runId={} syncService={} revisionStore={} dataSource={}",
                        runId, syncService != null, revisionStore != null, dataSource != null);
                return;
            }

            outcome.phase = "INITIALIZE";
            outcome.code = "INITIALIZATION_FAILED";
            UUID activeRevision = revisionStore.activeRevision(dataset);
            if (activeRevision == null) {
                LOGGER.info("ODII_SYNC_PHASE runId={} phase=INITIALIZE dataset={}", runId, dataset);
                activeRevision = revisionStore.initializeDataset(dataset, Instant.now());
            }
            if (activeRevision == null) {
                return;
            }
            outcome.revisionId = activeRevision;

            outcome.phase = "LEASE";
            outcome.code = "LEASE_DB_ERROR";
            LOGGER.info("ODII_SYNC_PHASE runId={} phase=LEASE trigger={} dataset={}", runId, trigger, dataset);
            SyncRunLease lease = acquireOrRenewLease(dataSource, dataset, OWNER_TOKEN, runId);
            if (lease == null) {
                outcome.status = "SKIPPED";
                outcome.code = "LEASE_NOT_ACQUIRED";
                return;
            }
            outcome.generation = lease.generation();

            outcome.phase = "SYNC";
            outcome.code = "SYNC_FAILED";
            LOGGER.info("ODII_SYNC_PHASE runId={} phase=SYNC trigger={} dataset={} activeRevision={}",
                    runId, trigger, dataset, activeRevision);

            var command = new OdiiSyncCommand(
                    dataset,
                    lease,
                    activeRevision,
                    languages,
                    false
            );

            var result = syncService.sync(command, new OdiiSyncObserver() {
                @Override
                public void phaseFailed(String ignoredDataset, UUID revisionId, String phase, String code) {
                    outcome.phase = phase;
                    outcome.code = code;
                    outcome.revisionId = revisionId;
                }
                @Override
                public void staged(String ignoredDataset, UUID revisionId, long count) {
                    outcome.revisionId = revisionId;
                    outcome.staged = count;
                }
                @Override
                public void stageCompleted(String ignoredDataset, UUID revisionId, long tombstoneCount) {
                    outcome.revisionId = revisionId;
                    outcome.tombstones = tombstoneCount;
                }
                @Override
                public void fetched(String ignoredDataset, UUID revisionId, long count) {
                    outcome.fetched += count;
                }
                @Override
                public void mapped(String ignoredDataset, UUID revisionId, long count) {
                    outcome.mapped += count;
                }
            });
            outcome.revisionId = result.stagedRevisionId();
            outcome.staged = result.itemCount();
            outcome.tombstones = result.tombstoneCount();
            if (result.status() == OdiiSyncStatus.PUBLISHED) {
                outcome.status = "COMPLETED";
                outcome.phase = "PUBLISH";
                outcome.code = null;
                outcome.published = result.itemCount();
            } else if ("SYNC_FAILED".equals(outcome.code)) {
                outcome.phase = "PUBLISH";
                outcome.code = result.status().name();
            }

        } catch (Exception exception) {
            // Do not log exception messages: upstream URLs and provider errors may contain secrets.
            outcome.exceptionType = exception.getClass().getName();
        } finally {
            if (history != null) {
                saveHistory(history, new OdiiSyncRunStore.Run(runId, dataset, trigger, startedAt,
                        Instant.now(), outcome.status, "COMPLETED".equals(outcome.status) ? null : outcome.phase,
                        outcome.code, outcome.revisionId, outcome.generation, outcome.fetched, outcome.mapped,
                        outcome.staged, outcome.published, outcome.tombstones));
            }
            String message = "ODII_SYNC_TERMINAL runId={} status={} reason={} phase={} trigger={} dataset={} "
                    + "revisionId={} leaseGeneration={} fetched={} mapped={} staged={} published={} tombstones={} exceptionType={}";
            Object[] arguments = {runId, outcome.status, outcome.code, outcome.phase, trigger, dataset,
                    outcome.revisionId, outcome.generation, outcome.fetched, outcome.mapped, outcome.staged,
                    outcome.published, outcome.tombstones, outcome.exceptionType};
            switch (outcome.status) {
                case "COMPLETED" -> LOGGER.info(message, arguments);
                case "SKIPPED" -> LOGGER.warn(message, arguments);
                default -> LOGGER.error(message, arguments);
            }
        }
    }

    private void saveHistory(OdiiSyncRunStore history, OdiiSyncRunStore.Run run) {
        try {
            history.save(run);
        } catch (RuntimeException exception) {
            LOGGER.warn("ODII_SYNC_HISTORY_FAILED runId={} lifecycle={} exceptionType={}",
                    run.id(), run.lifecycleStatus(), exception.getClass().getName());
        }
    }

    private static final class RunOutcome {
        private String status = "FAILED";
        private String phase = "DEPENDENCY_CHECK";
        private String code = "COMPONENT_CREATION_FAILED";
        private String exceptionType;
        private UUID revisionId;
        private Integer generation;
        private long fetched;
        private long mapped;
        private long staged;
        private long published;
        private long tombstones;
    }

    private SyncRunLease acquireOrRenewLease(DataSource dataSource, String dataset, String ownerToken, UUID runId)
            throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Instant now = Instant.now();
                Instant leaseUntil = now.plus(30, ChronoUnit.MINUTES);

                // Upsert lease
                var upsertSql = """
                        INSERT INTO onmaru.operations_sync_leases (dataset, owner_token, generation, lease_until)
                        VALUES (?, ?, 1, ?)
                        ON CONFLICT (dataset) DO UPDATE
                        SET owner_token = EXCLUDED.owner_token,
                            generation = onmaru.operations_sync_leases.generation + 1,
                            lease_until = EXCLUDED.lease_until
                        WHERE onmaru.operations_sync_leases.lease_until < ?
                           OR onmaru.operations_sync_leases.owner_token = ?
                        RETURNING generation
                        """;

                try (var statement = connection.prepareStatement(upsertSql)) {
                    statement.setString(1, dataset);
                    statement.setString(2, ownerToken);
                    statement.setObject(3, leaseUntil.atOffset(java.time.ZoneOffset.UTC));
                    statement.setObject(4, now.atOffset(java.time.ZoneOffset.UTC));
                    statement.setString(5, ownerToken);

                    try (var rs = statement.executeQuery()) {
                        if (rs.next()) {
                            int generation = rs.getInt("generation");
                            connection.commit();
                            return new SyncRunLease(runId, dataset, ownerToken, generation);
                        }
                    }
                }
                connection.rollback();
                return null;
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            }
        }
    }
}
