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
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
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

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        CompletableFuture.runAsync(() -> {
            try {
                // Initial delay to let application stabilize
                Thread.sleep(5000);
                LOGGER.info("triggering initial odii sync check on application ready");
                runSync("initial-boot");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                LOGGER.warn("initial odii sync execution encountered an error: {}", exception.getMessage(), exception);
            }
        });
    }

    @Scheduled(cron = "${onmaru.odii.sync.cron:0 0 3 * * *}")
    public void scheduledSync() {
        LOGGER.info("triggering scheduled odii sync run");
        runSync("scheduled-cron");
    }

    public synchronized void runSync(String triggerSource) {
        var syncService = syncServiceProvider.getIfAvailable();
        var revisionStore = revisionStoreProvider.getIfAvailable();
        var dataSource = dataSourceProvider.getIfAvailable();

        if (syncService == null || revisionStore == null || dataSource == null) {
            LOGGER.debug("odii sync skipped: required components not available (trigger: {})", triggerSource);
            return;
        }

        try {
            UUID activeRevision = revisionStore.activeRevision(dataset);
            if (activeRevision == null) {
                LOGGER.info("initializing dataset {} before sync", dataset);
                activeRevision = revisionStore.initializeDataset(dataset, Instant.now());
            }
            if (activeRevision == null) {
                LOGGER.error("odii sync aborted: dataset {} could not be initialized", dataset);
                return;
            }

            SyncRunLease lease = acquireOrRenewLease(dataSource, dataset, OWNER_TOKEN);
            if (lease == null) {
                LOGGER.info("odii sync skipped: could not acquire sync lease for dataset {}", dataset);
                return;
            }

            LOGGER.info("starting odii revision sync (trigger: {}, dataset: {}, activeRevision: {})",
                    triggerSource, dataset, activeRevision);

            var command = new OdiiSyncCommand(
                    dataset,
                    lease,
                    activeRevision,
                    languages,
                    false
            );

            var result = syncService.sync(command);
            LOGGER.info("odii revision sync completed with status: {}, staged items: {}, tombstones: {}",
                    result.status(), result.itemCount(), result.tombstoneCount());

        } catch (Exception exception) {
            LOGGER.error("odii sync failed (trigger: {}): {}", triggerSource, exception.getMessage(), exception);
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
                LOGGER.warn("failed to acquire lease: {}", e.getMessage());
                return null;
            }
        } catch (SQLException exception) {
            LOGGER.warn("failed to connect to database for lease: {}", exception.getMessage());
            return null;
        }
    }
}
