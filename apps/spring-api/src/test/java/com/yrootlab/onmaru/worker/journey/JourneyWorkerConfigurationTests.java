package com.yrootlab.onmaru.worker.journey;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.FakeSecretProvider;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.journey.run.AdvanceRunStageCommand;
import com.yrootlab.onmaru.journey.run.ClaimRunCommand;
import com.yrootlab.onmaru.journey.run.CreateRunCommand;
import com.yrootlab.onmaru.journey.run.FinishRunCommand;
import com.yrootlab.onmaru.journey.run.JourneyRunSnapshot;
import com.yrootlab.onmaru.journey.run.JourneyRunStage;
import com.yrootlab.onmaru.journey.run.JourneyRunStatus;
import com.yrootlab.onmaru.journey.run.JourneyRunStore;
import com.yrootlab.onmaru.journey.run.RunCommandReceipt;
import com.yrootlab.onmaru.journey.run.RunCommandResult;
import com.yrootlab.onmaru.journey.worker.AiProposalClient;
import com.yrootlab.onmaru.journey.worker.BaselinePlanner;
import com.yrootlab.onmaru.journey.worker.CandidatePayload;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyCandidateProvider;
import com.yrootlab.onmaru.journey.worker.InMemoryJourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyCandidate;
import com.yrootlab.onmaru.journey.worker.JourneyResultStore;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerQueue;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRequest;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerRunner;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerService;
import com.yrootlab.onmaru.journey.worker.JourneyWorkerTelemetry;
import com.yrootlab.onmaru.journey.worker.plan.AiJourneyContracts.Versions;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultCommand;
import com.yrootlab.onmaru.journey.worker.PersistJourneyResultResult;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionRequest;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionSubject;
import com.yrootlab.onmaru.operations.admission.InMemoryAdmissionStore;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.scheduling.run.JourneyRunSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import com.yrootlab.onmaru.integration.ai.HttpAiProposalClient;
import com.yrootlab.onmaru.journey.worker.plan.PlanExecuteAiProposalClient;
import com.yrootlab.onmaru.journey.worker.plan.JourneyPlanExecutionPorts.*;

class JourneyWorkerConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestDependencies.class, JourneyWorkerConfiguration.class)
            .withPropertyValues(
                    "onmaru.ai.base-url=http://localhost:18080",
                    "onmaru.ai.timeout=PT1S");

    @Test
    void wiresWorkerRunnerServiceAdaptersAndControllableInMemoryProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(JourneyWorkerRunner.class);
            assertThat(context).hasSingleBean(JourneyWorkerService.class);
            assertThat(context).hasSingleBean(JourneyWorkerQueue.class);
            assertThat(context).hasSingleBean(InMemoryJourneyCandidateProvider.class);
            assertThat(context).hasSingleBean(BaselinePlanner.class);
            assertThat(context).hasSingleBean(JourneyRunStore.class);
            assertThat(context).hasSingleBean(JourneyResultStore.class);
            assertThat(context.getBean(AiProposalClient.class)).isInstanceOf(HttpAiProposalClient.class);
        });
    }

    @Test
    void planExecuteFlagFailsClosedWithoutProductionAuthorityAndCapabilityPorts() {
        contextRunner.withPropertyValues("onmaru.ai.plan-execute-enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        });
    }

    @Test
    void selectsPlanExecuteOnlyWhenAllProductionExtensionPortsAreSupplied() {
        contextRunner.withPropertyValues("onmaru.ai.plan-execute-enabled=true")
                .withBean(SafeQueryFactory.class, () -> (request,candidates) -> { throw new UnsupportedOperationException(); })
                .withBean(CurrentVersions.class, () -> () -> { throw new UnsupportedOperationException(); })
                .withBean(ApprovedBindingStore.class, () -> (binding, deadline) -> { throw new UnsupportedOperationException(); })
                .withBean(QueryGrantIssuer.class, () -> (binding,deadline) -> { throw new UnsupportedOperationException(); })
                .withBean(ProgressCapabilityIssuer.class, () -> (binding,deadline) -> { throw new UnsupportedOperationException(); })
                .withBean(ExecutionResultMapper.class, () -> (binding,result,candidates) -> { throw new UnsupportedOperationException(); })
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiProposalClient.class);
                    assertThat(context.getBean(AiProposalClient.class)).isInstanceOf(PlanExecuteAiProposalClient.class);
                });
    }

    @Test
    void fullProductionPlanExecuteConfigurationBootsCleanlyWithRealDefaultPorts() throws Exception {
        Class<?> internalProgressConfig = Class.forName(
                "com.yrootlab.onmaru.web.internal.ai.progress.InternalAiProgressConfiguration");
        Class<?> queryToolConfig = Class.forName(
                "com.yrootlab.onmaru.web.internal.mcp.journey.JourneyQueryToolConfiguration");
        new ApplicationContextRunner()
                .withUserConfiguration(
                        TestDependencies.class,
                        JourneyWorkerConfiguration.class,
                        internalProgressConfig,
                        queryToolConfig)
                .withPropertyValues(
                        "onmaru.ai.base-url=http://localhost:18080",
                        "onmaru.ai.timeout=PT1S",
                        "onmaru.ai.plan-execute-enabled=true",
                        "onmaru.ai.versions.corpus-revision=corpus-prod-1")
                .withBean(CurrentVersions.class, () -> () -> new Versions(
                        "corpus-prod-1", "sha256:" + "a".repeat(64), "questions-1", 7L))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiProposalClient.class);
                    assertThat(context.getBean(AiProposalClient.class)).isInstanceOf(PlanExecuteAiProposalClient.class);
                    assertThat(context).hasSingleBean(SafeQueryFactory.class);
                    assertThat(context).hasSingleBean(CurrentVersions.class);
                    assertThat(context).hasSingleBean(ExplorationContextReader.class);
                    assertThat(context).hasSingleBean(PublicPlaceEligibilityChecker.class);
                    assertThat(context).hasSingleBean(ExecutionResultMapper.class);
                    assertThat(context.getBean(CurrentVersions.class).current().corpusRevision())
                            .isEqualTo("corpus-prod-1");
                });
    }

    @Test
    void fullProductionPlanExecuteConfigurationBootsWhenProgressConfigInitializesFirst() throws Exception {
        Class<?> internalProgressConfig = Class.forName(
                "com.yrootlab.onmaru.web.internal.ai.progress.InternalAiProgressConfiguration");
        Class<?> queryToolConfig = Class.forName(
                "com.yrootlab.onmaru.web.internal.mcp.journey.JourneyQueryToolConfiguration");
        new ApplicationContextRunner()
                .withUserConfiguration(
                        TestDependencies.class,
                        internalProgressConfig,
                        queryToolConfig,
                        JourneyWorkerConfiguration.class)
                .withPropertyValues(
                        "onmaru.ai.base-url=http://localhost:18080",
                        "onmaru.ai.timeout=PT1S",
                        "onmaru.ai.plan-execute-enabled=true",
                        "onmaru.ai.versions.corpus-revision=corpus-prod-1")
                .withBean(CurrentVersions.class, () -> () -> new Versions(
                        "corpus-prod-1", "sha256:" + "a".repeat(64), "questions-1", 7L))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(AiProposalClient.class);
                    assertThat(context.getBean(AiProposalClient.class)).isInstanceOf(PlanExecuteAiProposalClient.class);
                    assertThat(context.getBean(ApprovedBindingStore.class)).isNotNull();
                });
    }

    @Test
    void backsOffDatabaseBoundWorkerBeansWhenNoDataSourceExists() {
        new ApplicationContextRunner()
                .withUserConfiguration(NoDataSourceDependencies.class, JourneyWorkerConfiguration.class)
                .withPropertyValues(
                        "onmaru.ai.base-url=http://localhost:18080",
                        "onmaru.ai.timeout=PT1S")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(JourneyWorkerQueue.class);
                    assertThat(context).hasSingleBean(InMemoryJourneyCandidateProvider.class);
                    assertThat(context).hasSingleBean(BaselinePlanner.class);
                    assertThat(context).doesNotHaveBean(JourneyRunStore.class);
                    assertThat(context).doesNotHaveBean(JourneyResultStore.class);
                    assertThat(context).doesNotHaveBean(JourneyWorkerService.class);
                    assertThat(context).doesNotHaveBean(JourneyWorkerRunner.class);
                });
    }

    @Test
    void aiProposalClientUsesHttp11WithoutH2cUpgrade() throws Exception {
        var runId = UUID.randomUUID();
        try (var socket = new ServerSocket(0)) {
            var captured = new CompletableFuture<List<String>>();
            var serverThread = Thread.ofVirtual().start(() -> {
                try (var client = socket.accept()) {
                    var in = client.getInputStream();
                    var lines = new ArrayList<String>();
                    int contentLength = 0;
                    var lineBuf = new java.io.ByteArrayOutputStream();
                    int b;
                    while ((b = in.read()) != -1) {
                        if (b == '\n') {
                            var s = lineBuf.toString(StandardCharsets.US_ASCII).replace("\r", "");
                            lineBuf.reset();
                            if (s.isEmpty()) {
                                break;
                            }
                            lines.add(s);
                            if (s.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                                contentLength = Integer.parseInt(s.substring("content-length:".length()).trim());
                            }
                        } else {
                            lineBuf.write(b);
                        }
                    }
                    if (contentLength > 0) {
                        in.readNBytes(contentLength);
                    }
                    captured.complete(lines);
                    var body = "{\"schemaVersion\":\"internal.ai.v1\",\"runId\":\"%s\",\"orderedRefs\":[\"p-1\"],\"outcome\":\"PROPOSAL\"}"
                            .formatted(runId);
                    var response = "HTTP/1.1 200 OK\r\n"
                            + "Content-Type: application/json\r\n"
                            + "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
                            + "Connection: close\r\n\r\n"
                            + body;
                    client.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
                    client.getOutputStream().flush();
                } catch (Exception exception) {
                    captured.completeExceptionally(exception);
                }
            });

            new ApplicationContextRunner()
                    .withUserConfiguration(TestDependencies.class, JourneyWorkerConfiguration.class)
                    .withPropertyValues(
                            "onmaru.ai.base-url=http://127.0.0.1:" + socket.getLocalPort(),
                            "onmaru.ai.timeout=PT2S",
                            "onmaru.ai.streaming-enabled=false")
                    .run(context -> {
                        var client = context.getBean(AiProposalClient.class);
                        var now = Instant.parse("2026-11-16T01:00:00Z");
                        var plan = client.propose(
                                new JourneyWorkerRequest(
                                        runId,
                                        UUID.randomUUID(),
                                        "member:" + UUID.randomUUID(),
                                        1,
                                        "rev-1",
                                        null,
                                        "한옥 코스",
                                        0,
                                        "req-1",
                                        "trace-1",
                                        now.plusSeconds(10)),
                                new CandidatePayload("rev-1", List.of(new JourneyCandidate("p-1"))));
                        assertThat(plan.orderedRefs()).containsExactly("p-1");
                    });

            serverThread.join(2000);
            var headers = captured.get().stream().map(line -> line.toLowerCase(Locale.ROOT)).toList();
            assertThat(headers.getFirst()).endsWith(" http/1.1");
            assertThat(headers).noneMatch(line -> line.startsWith("upgrade:") || line.startsWith("http2-settings:"));
        }
    }

    @Test
    void journeyWorkerRunnerReleasesActiveAdmissionSlotWhenRunCompletes() {
        var memberId = UUID.randomUUID();
        var clock = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), java.time.ZoneOffset.UTC);
        var admissionService = new AdmissionService(new InMemoryAdmissionStore(), clock);
        var admissionPolicy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.ai", SubjectType.MEMBER, 5, Duration.ofDays(1), 1)));
        var subject = new AdmissionRequest("journey.ai", new AdmissionSubject(SubjectType.MEMBER, memberId.toString()));
        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isFalse();

        var queue = new InMemoryJourneyWorkerQueue();
        var runId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var actorKey = "member:" + memberId;
        queue.enqueue(new JourneyWorkerRequest(
                runId,
                explorationId,
                actorKey,
                1,
                "rev-1",
                null,
                "한옥 코스",
                0,
                "req-1",
                "trace-1",
                clock.instant().plusSeconds(20)));

        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(TestDependencies.class, JourneyWorkerConfiguration.class)
                .withPropertyValues(
                        "onmaru.ai.base-url=http://127.0.0.1:18080",
                        "onmaru.ai.timeout=PT1S")
                .withBean(AdmissionService.class, () -> admissionService)
                .withBean(AdmissionPolicy.class, () -> admissionPolicy)
                .withBean("durableJourneyWorkerQueue", JourneyWorkerQueue.class, () -> queue)
                .withBean("journeyRunStore", JourneyRunStore.class, () -> new StubJourneyRunStore(runId, explorationId, actorKey, clock.instant()))
                .withBean("journeyResultStore", JourneyResultStore.class, () -> command -> PersistJourneyResultResult.PERSISTED)
                .withBean("aiProposalClient", AiProposalClient.class, () -> (request, payload) -> new com.yrootlab.onmaru.journey.worker.JourneyWorkerPlan(
                        List.of("p-1"), "INITIAL_BOARD"))
                .withBean("catalogCandidateProvider", com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider.class,
                        () -> request -> new CandidatePayload("rev-1", List.of(new JourneyCandidate("p-1"))),
                        bd -> bd.setPrimary(true))
                .run(context -> {
                    var runner = context.getBean(JourneyWorkerRunner.class);
                    var result = runner.drain();
                    assertThat(result.processed()).isEqualTo(1);
                });

        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
    }

    @Test
    void journeyWorkerRunnerReleasesActiveAdmissionSlotWhenRunFailsExplicitly() {
        var memberId = UUID.randomUUID();
        var clock = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), java.time.ZoneOffset.UTC);
        var admissionService = new AdmissionService(new InMemoryAdmissionStore(), clock);
        var admissionPolicy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.ai", SubjectType.MEMBER, 5, Duration.ofDays(1), 1)));
        var subject = new AdmissionRequest("journey.ai", new AdmissionSubject(SubjectType.MEMBER, memberId.toString()));
        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isFalse();

        var queue = new InMemoryJourneyWorkerQueue();
        var runId = UUID.randomUUID();
        var explorationId = UUID.randomUUID();
        var actorKey = "member:" + memberId;
        queue.enqueue(new JourneyWorkerRequest(
                runId,
                explorationId,
                actorKey,
                1,
                "rev-1",
                null,
                "한옥 코스",
                0,
                "req-1",
                "trace-1",
                clock.instant().plusSeconds(20)));

        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(TestDependencies.class, JourneyWorkerConfiguration.class)
                .withPropertyValues(
                        "onmaru.ai.base-url=http://127.0.0.1:18080",
                        "onmaru.ai.timeout=PT1S")
                .withBean(AdmissionService.class, () -> admissionService)
                .withBean(AdmissionPolicy.class, () -> admissionPolicy)
                .withBean("durableJourneyWorkerQueue", JourneyWorkerQueue.class, () -> queue)
                .withBean("journeyRunStore", JourneyRunStore.class, () -> new StubJourneyRunStore(runId, explorationId, actorKey, clock.instant()))
                .withBean("journeyResultStore", JourneyResultStore.class, () -> command -> PersistJourneyResultResult.PERSISTED)
                .withBean("aiProposalClient", AiProposalClient.class, () -> (request, payload) -> {
                    throw new com.yrootlab.onmaru.journey.worker.AiProposalException(
                            com.yrootlab.onmaru.journey.worker.WorkerDegradedReason.AI_QUOTA_EXCEEDED,
                            12_000L);
                })
                .withBean("catalogCandidateProvider", com.yrootlab.onmaru.journey.worker.JourneyCandidateProvider.class,
                        () -> request -> new CandidatePayload("rev-1", List.of()),
                        bd -> bd.setPrimary(true))
                .run(context -> {
                    var runner = context.getBean(JourneyWorkerRunner.class);
                    var result = runner.drain();
                    assertThat(result.processed()).isEqualTo(1);
                    assertThat(result.outcomes()).containsExactly(
                            com.yrootlab.onmaru.journey.worker.JourneyWorkerOutcome.FAILED);
                });

        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
    }

    @Test
    void journeyRunSweeperReleasesActiveAdmissionSlotForExpiredRuns() {
        var memberId = UUID.randomUUID();
        var clock = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), java.time.ZoneOffset.UTC);
        var admissionService = new AdmissionService(new InMemoryAdmissionStore(), clock);
        var admissionPolicy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.ai", SubjectType.MEMBER, 5, Duration.ofDays(1), 1)));
        var subject = new AdmissionRequest("journey.ai", new AdmissionSubject(SubjectType.MEMBER, memberId.toString()));
        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isFalse();

        var expiredRun = new JourneyRunSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "member:" + memberId,
                0,
                JourneyRunStatus.FAILED,
                null,
                null,
                clock.instant().minusSeconds(30),
                clock.instant().minusSeconds(5),
                clock.instant().minusSeconds(29),
                2,
                "RUN_TIMEOUT",
                "LLM");

        new ApplicationContextRunner()
                .withAllowBeanDefinitionOverriding(true)
                .withUserConfiguration(TestDependencies.class, JourneyWorkerConfiguration.class)
                .withPropertyValues(
                        "onmaru.ai.base-url=http://127.0.0.1:18080",
                        "onmaru.ai.timeout=PT1S")
                .withBean(AdmissionService.class, () -> admissionService)
                .withBean(AdmissionPolicy.class, () -> admissionPolicy)
                .withBean(JourneyRunStore.class, () -> new StubJourneyRunStore(
                        expiredRun.id(), expiredRun.explorationId(), expiredRun.actorKey(), clock.instant(), List.of(expiredRun)))
                .run(context -> {
                    var sweeper = context.getBean(JourneyRunSweeper.class);
                    assertThat(sweeper.sweep()).isEqualTo(1);
                });

        assertThat(admissionService.admitActive(subject, admissionPolicy).allowed()).isTrue();
    }

    private static final class StubJourneyRunStore implements JourneyRunStore {

        private final JourneyRunSnapshot runningSnapshot;
        private final List<JourneyRunSnapshot> expiredSnapshots;

        StubJourneyRunStore(UUID runId, UUID explorationId, String actorKey, Instant now) {
            this(runId, explorationId, actorKey, now, List.of());
        }

        StubJourneyRunStore(
                UUID runId,
                UUID explorationId,
                String actorKey,
                Instant now,
                List<JourneyRunSnapshot> expiredSnapshots) {
            this.runningSnapshot = new JourneyRunSnapshot(
                    runId, explorationId, actorKey, 0, JourneyRunStatus.RUNNING, null, null,
                    now, now.plusSeconds(20), now, 2, null, "LLM");
            this.expiredSnapshots = expiredSnapshots;
        }

        @Override
        public RunCommandResult create(CreateRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public RunCommandResult claim(ClaimRunCommand command) {
            return new RunCommandResult(
                    new RunCommandReceipt(command.runId(), JourneyRunStatus.RUNNING, null, null, 2),
                    false);
        }

        @Override
        public RunCommandResult advance(AdvanceRunStageCommand command) {
            return new RunCommandResult(
                    new RunCommandReceipt(command.runId(), JourneyRunStatus.RUNNING, command.nextStage(), null, command.expectedGeneration()),
                    false);
        }

        @Override
        public RunCommandResult finish(FinishRunCommand command) {
            return new RunCommandResult(
                    new RunCommandReceipt(command.runId(), JourneyRunStatus.COMPLETED, null, command.outcome(), command.expectedGeneration()),
                    false);
        }

        @Override
        public RunCommandResult cancel(com.yrootlab.onmaru.journey.cancellation.CancelJourneyRunCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<JourneyRunSnapshot> expireDueRuns(Instant now) {
            return expiredSnapshots;
        }

        @Override
        public Optional<JourneyRunSnapshot> find(UUID runId, String actorKey) {
            return Optional.of(runningSnapshot);
        }
    }

    static class TestDependencies {

        @org.springframework.context.annotation.Bean
        SecretProvider secretProvider() {
            return new FakeSecretProvider();
        }

        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @org.springframework.context.annotation.Bean
        JourneyWorkerTelemetry telemetry() {
            return ignored -> { };
        }

        @org.springframework.context.annotation.Bean
        DataSource dataSource() {
            return new DataSource() {
                @Override
                public Connection getConnection() throws SQLException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public Connection getConnection(String username, String password) throws SQLException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public PrintWriter getLogWriter() {
                    return null;
                }

                @Override
                public void setLogWriter(PrintWriter out) {
                }

                @Override
                public void setLoginTimeout(int seconds) {
                }

                @Override
                public int getLoginTimeout() {
                    return 0;
                }

                @Override
                public Logger getParentLogger() throws SQLFeatureNotSupportedException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public <T> T unwrap(Class<T> iface) throws SQLException {
                    throw new SQLFeatureNotSupportedException();
                }

                @Override
                public boolean isWrapperFor(Class<?> iface) {
                    return false;
                }
            };
        }
    }

    static class NoDataSourceDependencies {

        @org.springframework.context.annotation.Bean
        SecretProvider secretProvider() {
            return new FakeSecretProvider();
        }

        @org.springframework.context.annotation.Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @org.springframework.context.annotation.Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @org.springframework.context.annotation.Bean
        JourneyWorkerTelemetry telemetry() {
            return ignored -> { };
        }
    }
}
