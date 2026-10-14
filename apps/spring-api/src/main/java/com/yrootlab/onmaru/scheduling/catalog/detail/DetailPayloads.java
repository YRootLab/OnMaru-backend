package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.fasterxml.jackson.databind.JsonNode;

/** common은 객체, intro와 info는 배열이다. */
public record DetailPayloads(JsonNode common, JsonNode intro, JsonNode info) {
}
