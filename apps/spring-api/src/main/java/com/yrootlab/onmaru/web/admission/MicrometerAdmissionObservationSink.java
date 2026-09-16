package com.yrootlab.onmaru.web.admission;

import com.yrootlab.onmaru.operations.admission.AdmissionDecision;
import com.yrootlab.onmaru.operations.admission.AdmissionObservationSink;
import com.yrootlab.onmaru.operations.admission.AdmissionScope;
import io.micrometer.core.instrument.MeterRegistry;

final class MicrometerAdmissionObservationSink implements AdmissionObservationSink {

    private final MeterRegistry meterRegistry;

    MicrometerAdmissionObservationSink(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void recordDecision(AdmissionScope scope, AdmissionDecision decision) {
        meterRegistry.counter(
                "onmaru.admission.decisions",
                "operation", scope.operation(),
                "subjectType", scope.subjectType().name(),
                "decision", decision.allowed() ? "allowed" : "rejected",
                "reason", decision.reason() == null ? "none" : decision.reason().name()
        ).increment();
    }

    @Override
    public void recordRelease(AdmissionScope scope) {
        meterRegistry.counter(
                "onmaru.admission.releases",
                "operation", scope.operation(),
                "subjectType", scope.subjectType().name()
        ).increment();
    }
}
