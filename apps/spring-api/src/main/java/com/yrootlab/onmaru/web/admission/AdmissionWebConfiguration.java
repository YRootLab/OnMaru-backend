package com.yrootlab.onmaru.web.admission;

import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionStore;
import com.yrootlab.onmaru.operations.admission.InMemoryAdmissionStore;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
public class AdmissionWebConfiguration {

    @Bean
    AdmissionStore admissionStore() {
        return new InMemoryAdmissionStore();
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    AdmissionService admissionService(AdmissionStore store, Clock clock) {
        return new AdmissionService(store, clock);
    }

    @Bean
    AdmissionPolicy admissionPolicy() {
        return new AdmissionPolicy(Duration.ofMinutes(1), List.of(
                new OperationBudget("login.start", SubjectType.IP, 20)
        ));
    }

    @Bean
    TrustedProxyProperties trustedProxyProperties() {
        return new TrustedProxyProperties(List.of());
    }

    @Bean
    AdmissionFilter admissionFilter(
            AdmissionService admissionService,
            AdmissionPolicy admissionPolicy,
            TrustedProxyProperties trustedProxyProperties,
            ObjectProvider<ClientIdentityResolver> resolverProvider
    ) {
        var resolver = resolverProvider.getIfAvailable(() -> new ClientIdentityResolver(trustedProxyProperties));
        return new AdmissionFilter(admissionService, admissionPolicy, resolver);
    }
}
