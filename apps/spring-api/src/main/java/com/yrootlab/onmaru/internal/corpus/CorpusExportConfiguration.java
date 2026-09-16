package com.yrootlab.onmaru.internal.corpus;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class CorpusExportConfiguration {

    @Bean
    InMemoryCorpusExportStore corpusExportStore() {
        return new InMemoryCorpusExportStore();
    }

    @Bean
    CorpusExportService corpusExportService(InMemoryCorpusExportStore corpusExportStore) {
        return new CorpusExportService(corpusExportStore);
    }
}
