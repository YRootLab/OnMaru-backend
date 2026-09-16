package com.yrootlab.onmaru.web.admission;

import com.yrootlab.onmaru.operations.admission.AdmissionPolicy;
import com.yrootlab.onmaru.operations.admission.AdmissionObservationSink;
import com.yrootlab.onmaru.operations.admission.AdmissionService;
import com.yrootlab.onmaru.operations.admission.AdmissionStore;
import com.yrootlab.onmaru.operations.admission.InMemoryAdmissionStore;
import com.yrootlab.onmaru.operations.admission.OperationBudget;
import com.yrootlab.onmaru.operations.admission.SubjectType;
import com.yrootlab.onmaru.persistence.operations.admission.JdbcAdmissionStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

@Configuration
public class AdmissionWebConfiguration {

    @Bean
    @ConditionalOnBean(DataSource.class)
    AdmissionStore jdbcAdmissionStore(DataSource dataSource) {
        return new JdbcAdmissionStore(dataSource);
    }

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
                new OperationBudget("journey.ai", SubjectType.MEMBER, 5, Duration.ofDays(1), 1)
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
