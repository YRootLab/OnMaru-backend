package com.yrootlab.onmaru.internal.corpus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

final class CorpusHashes {

    private static final ObjectMapper HASH_MAPPER = JsonMapper.builder()
            .configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true)
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    private CorpusHashes() {
    }

    static String prefixedHash(Map<String, Object> payload) {
        return "sha256:" + rawHash(payload);
    }

    static String chunkHash(Map<String, Object> payload) {
        return rawHash(payload);
    }

    static String rawHash(Map<String, Object> payload) {
        return sha256(canonicalJson(payload));
    }

    private static String canonicalJson(Map<String, Object> payload) {
        try {
            return HASH_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("payload cannot be hashed", exception);
        }
    }

    private static String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
