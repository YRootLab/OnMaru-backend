package com.yrootlab.onmaru.scheduling.audio;

import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.OdiiRevisionSyncService;
import com.yrootlab.onmaru.audio.sync.OdiiSyncCommand;
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
        String phase = "DEPENDENCY_CHECK";
        var syncService = syncServiceProvider.getIfAvailable();
        var revisionStore = revisionStoreProvider.getIfAvailable();
        var dataSource = dataSourceProvider.getIfAvailable();

        if (syncService == null || revisionStore == null || dataSource == null) {
            LOGGER.warn("ODII_SYNC_TERMINAL runId={} status=SKIPPED reason=MISSING_COMPONENT phase={} trigger={} "
                            + "syncService={} revisionStore={} dataSource={}",
                    runId, phase, triggerSource, syncService != null, revisionStore != null, dataSource != null);
            return;
        }

        try {
            phase = "INITIALIZE";
            LOGGER.info("ODII_SYNC_STARTED runId={} trigger={} dataset={}", runId, triggerSource, dataset);
            UUID activeRevision = revisionStore.activeRevision(dataset);
            if (activeRevision == null) {
                LOGGER.info("ODII_SYNC_PHASE runId={} phase=INITIALIZE dataset={}", runId, dataset);
                activeRevision = revisionStore.initializeDataset(dataset, Instant.now());
            }
            if (activeRevision == null) {
                LOGGER.error("ODII_SYNC_TERMINAL runId={} status=FAILED phase={} trigger={} dataset={}",
                        runId, phase, triggerSource, dataset);
                return;
            }

            phase = "LEASE";
            LOGGER.info("ODII_SYNC_PHASE runId={} phase={} trigger={} dataset={}", runId, phase, triggerSource, dataset);
            SyncRunLease lease = acquireOrRenewLease(dataSource, dataset, OWNER_TOKEN);
            if (lease == null) {
                LOGGER.warn("ODII_SYNC_TERMINAL runId={} status=SKIPPED reason=LEASE_NOT_ACQUIRED phase={} trigger={} dataset={}",
                        runId, phase, triggerSource, dataset);
                return;
            }

            phase = "SYNC";
            LOGGER.info("ODII_SYNC_PHASE runId={} phase={} trigger={} dataset={} activeRevision={}",
                    runId, phase, triggerSource, dataset, activeRevision);

            var command = new OdiiSyncCommand(
                    dataset,
                    lease,
                    activeRevision,
                    languages,
                    false
            );

            var result = syncService.sync(command);
            boolean published = result.status() == com.yrootlab.onmaru.audio.sync.OdiiSyncStatus.PUBLISHED;
            LOGGER.info("ODII_SYNC_TERMINAL runId={} status={} phase={} trigger={} dataset={} "
                            + "revisionId={} staged={} tombstones={}",
                    runId, published ? "COMPLETED" : "FAILED",
                    published ? "PUBLISH" : result.status(), triggerSource, dataset,
                    result.stagedRevisionId(), result.itemCount(), result.tombstoneCount());

        } catch (Exception exception) {
            // Do not log exception messages: upstream URLs and provider errors may contain secrets.
            LOGGER.error("ODII_SYNC_TERMINAL runId={} status=FAILED phase={} trigger={} dataset={} exceptionType={}",
                    runId, phase, triggerSource, dataset, exception.getClass().getName());
        }
    }

    private SyncRunLease acquireOrRenewLease(DataSource dataSource, String dataset, String ownerToken) {
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
                            return new SyncRunLease(UUID.randomUUID(), dataset, ownerToken, generation);
                        }
                    }
                }
                connection.rollback();
                return null;
            } catch (SQLException e) {
                connection.rollback();
                LOGGER.warn("ODII_SYNC_LEASE_FAILED phase=LEASE exceptionType={}", e.getClass().getName());
                return null;
            }
        } catch (SQLException exception) {
            LOGGER.warn("ODII_SYNC_LEASE_FAILED phase=LEASE exceptionType={}", exception.getClass().getName());
            return null;
        }
    }
}
