package com.yrootlab.onmaru.scheduling.catalog;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

@Configuration
@EnableScheduling
@ConditionalOnExpression("'${onmaru.discovery.operator.action:}' == ''")
public class CatalogSchedulingConfiguration {
}
