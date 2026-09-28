package com.yrootlab.onmaru.internal.corpus;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import com.yrootlab.onmaru.catalog.application.query.hanok.HanokListStore;

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

    @Bean
    HanokCorpusRevisionFactory hanokCorpusRevisionFactory(HanokListStore hanokListStore) {
        return new HanokCorpusRevisionFactory(hanokListStore);
    }
}
