package com.yrootlab.onmaru.web.admission;

import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionObservationSink;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionStore;
import com.yrootlab.onmaru.operations.admission.InMemoryAdmissionStore;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableConfigurationProperties(JourneyAiTestQuotaProperties.class)
public class AdmissionWebConfiguration {

    @Bean
    @ConditionalOnMissingBean(AdmissionStore.class)
    AdmissionStore admissionStore() {
        return new InMemoryAdmissionStore();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AdmissionService admissionService(
            AdmissionStore store,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        var meterRegistry = meterRegistryProvider.getIfAvailable();
        var sink = meterRegistry == null
                ? AdmissionObservationSink.NOOP
                : new MicrometerAdmissionObservationSink(meterRegistry);
        return new AdmissionService(store, clock, sink);
    }

    @Bean
    AdmissionPolicy admissionPolicy() {
        return new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("login.start", SubjectType.IP, 20),
                new OperationBudget("journey.ai", SubjectType.GUEST, 2, Duration.ofDays(1), 1),
                new OperationBudget("journey.ai", SubjectType.MEMBER, 5, Duration.ofDays(1), 1),
                new OperationBudget("presence.warmth", SubjectType.CLIENT_ID, 3, Duration.ofSeconds(1)),
                new OperationBudget("presence.warmth.room", SubjectType.ROOM_ID, 50, Duration.ofSeconds(1)),
                // limit=1000/day는 실질적으로 무제한; activeLimit=10이 IP당 동시 SSE 상한
                new OperationBudget("presence.stream", SubjectType.IP, 1000, Duration.ofDays(1), 10)
        ));
    }

    @Bean
    TrustedProxyProperties trustedProxyProperties(
            @Value("${onmaru.web.trusted-proxy-ips:127.0.0.1}") String rawIps) {
        var ips = Arrays.stream(rawIps.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        return new TrustedProxyProperties(ips);
    }

    @Bean
    ClientIdentityResolver clientIdentityResolver(TrustedProxyProperties trustedProxyProperties) {
        return new ClientIdentityResolver(trustedProxyProperties);
    }

    @Bean
    AdmissionFilter admissionFilter(
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            ClientIdentityResolver clientIdentityResolver
    ) {
        return new AdmissionFilter(admissionService, admissionPolicy, clientIdentityResolver);
    }
}
