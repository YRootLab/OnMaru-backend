package com.yrootlab.onmaru.integration.ai.plan;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("onmaru.ai.versions")
public record AiPlanVersionsProperties(
        String corpusRevision,
        String configHash,
        String questionDictionaryVersion,
        long sourceRevocationSequence) {

    public static final String DEFAULT_CORPUS_REVISION = "corpus-1";
    public static final String DEFAULT_CONFIG_HASH = "sha256:" + "a".repeat(64);
    public static final String DEFAULT_QUESTION_DICTIONARY_VERSION = "questions-1";
    public static final long DEFAULT_SOURCE_REVOCATION_SEQUENCE = 7L;

    public AiPlanVersionsProperties {
        if (corpusRevision == null || corpusRevision.isBlank()) {
            corpusRevision = DEFAULT_CORPUS_REVISION;
        }
        if (configHash == null || configHash.isBlank()) {
            configHash = DEFAULT_CONFIG_HASH;
        }
        if (questionDictionaryVersion == null || questionDictionaryVersion.isBlank()) {
            questionDictionaryVersion = DEFAULT_QUESTION_DICTIONARY_VERSION;
        }
        if (sourceRevocationSequence < 0) {
            sourceRevocationSequence = DEFAULT_SOURCE_REVOCATION_SEQUENCE;
        }
    }

    public static AiPlanVersionsProperties defaults() {
        return new AiPlanVersionsProperties(
                DEFAULT_CORPUS_REVISION,
                DEFAULT_CONFIG_HASH,
                DEFAULT_QUESTION_DICTIONARY_VERSION,
                DEFAULT_SOURCE_REVOCATION_SEQUENCE);
    }
}
