package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.fasterxml.jackson.databind.JsonNode;

/** common은 객체, intro는 배열이다. info는 일괄 수집에서 비워 두며 사용자 조회 시에만 채운다. */
public record DetailPayloads(JsonNode common, JsonNode intro, JsonNode info) {
}
