package com.yrootlab.onmaru.operations.admission;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AdmissionServiceTests {

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-15T03:00:05Z"), ZoneOffset.UTC);

    @Test
    void atomicStoreApprovesNoMoreThanOperationLimitUnderConcurrentRequests() throws Exception {
        var store = new InMemoryAdmissionStore();
        var service = new AdmissionService(store, clock);
        var policy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("review.write", SubjectType.MEMBER, 5)
        ));

        var results = Collections.synchronizedList(new ArrayList<AdmissionDecision>());
        var ready = new CountDownLatch(20);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(20)) {
            for (int index = 0; index < 20; index++) {
                executor.submit(() -> {
                    ready.countDown();
                    start.await(1, TimeUnit.SECONDS);
                    results.add(service.admit(new AdmissionRequest(
                            "review.write",
                            new AdmissionSubject(SubjectType.MEMBER, "member-1")
                    ), policy));
                    return null;
                });
            }
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(results).hasSize(20);
        assertThat(results.stream().filter(AdmissionDecision::allowed)).hasSize(5);
        assertThat(results.stream().filter(decision -> !decision.allowed())).hasSize(15);
        assertThat(results.stream()
                .filter(decision -> !decision.allowed())
                .map(AdmissionDecision::retryAfter)
                .distinct()).containsExactly(Duration.ofSeconds(55));
    }

    @Test
    void memberAndIpSubjectsHaveSeparateOperationBudgets() {
        var store = new InMemoryAdmissionStore();
        var service = new AdmissionService(store, clock);
        var policy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("login.start", SubjectType.IP, 1),
                new OperationBudget("review.write", SubjectType.MEMBER, 1)
        ));

        var firstIp = service.admit(new AdmissionRequest(
                "login.start",
                new AdmissionSubject(SubjectType.IP, "203.0.113.10")
        ), policy);
        var secondIp = service.admit(new AdmissionRequest(
                "login.start",
                new AdmissionSubject(SubjectType.IP, "203.0.113.10")
        ), policy);
        var firstMember = service.admit(new AdmissionRequest(
                "review.write",
                new AdmissionSubject(SubjectType.MEMBER, "member-1")
        ), policy);

        assertThat(firstIp.allowed()).isTrue();
        assertThat(secondIp.allowed()).isFalse();
        assertThat(secondIp.retryAfter()).isEqualTo(Duration.ofSeconds(55));
        assertThat(firstMember.allowed()).isTrue();
    }

    @Test
    void nextWindowResetsConsumedBudget() {
        var store = new InMemoryAdmissionStore();
        var firstWindow = new AdmissionService(store, clock);
        var secondWindow = new AdmissionService(
                store,
                Clock.fixed(Instant.parse("2026-09-15T03:01:00Z"), ZoneOffset.UTC)
        );
        var policy = new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("journey.command", SubjectType.MEMBER, 1)
        ));
        var request = new AdmissionRequest(
                "journey.command",
                new AdmissionSubject(SubjectType.MEMBER, "member-1")
        );

        assertThat(firstWindow.admit(request, policy).allowed()).isTrue();
        assertThat(firstWindow.admit(request, policy).allowed()).isFalse();
        assertThat(secondWindow.admit(request, policy).allowed()).isTrue();
    }
}
