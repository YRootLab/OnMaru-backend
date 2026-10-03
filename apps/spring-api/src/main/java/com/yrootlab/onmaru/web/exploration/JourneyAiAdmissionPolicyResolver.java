package com.yrootlab.onmaru.web.exploration;

import com.yrootlab.onmaru.journey.exploration.ExplorationActor;
import com.yrootlab.onmaru.journey.exploration.ExplorationActorType;
import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class JourneyAiAdmissionPolicyResolver {

    private final AdmissionPolicy operationalPolicy;
    private final JourneyAiTestQuotaProperties testQuota;

    public JourneyAiAdmissionPolicyResolver(AdmissionPolicy operationalPolicy, JourneyAiTestQuotaProperties testQuota) {
        testQuota.validate();
        this.operationalPolicy = operationalPolicy;
        this.testQuota = testQuota;
    }

    public AdmissionPolicy resolve(ExplorationActor actor, Instant now) {
        if (actor.type() != ExplorationActorType.MEMBER
                || now.isBefore(testQuota.getStart()) || !now.isBefore(testQuota.getEnd())) {
            return operationalPolicy;
        }
        var limit = testQuota.getExemptMemberIds().contains(UUID.fromString(actor.subject()))
                ? Integer.MAX_VALUE : testQuota.getLimit();
        return new AdmissionPolicy(operationalPolicy.window(), List.of(new OperationBudget(
                "journey.ai", SubjectType.MEMBER, limit,
                Duration.between(testQuota.getStart(), testQuota.getEnd()), 1, testQuota.getStart())));
    }
}
