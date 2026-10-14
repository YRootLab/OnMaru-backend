package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 한 장소의 상세 호출 결과다.
 * stopRun이 true이면 이번 실행의 남은 호출을 중단한다(한도 초과, 인증 실패, 장애 등).
 */
public record DetailFetchResult(Status status, JsonNode payload, String errorCode, boolean stopRun) {

    public enum Status { OK, FAILED }

    public static DetailFetchResult ok(JsonNode payload) {
        return new DetailFetchResult(Status.OK, payload, null, false);
    }

    public static DetailFetchResult failed(String errorCode, boolean stopRun) {
        return new DetailFetchResult(Status.FAILED, null, errorCode, stopRun);
    }
}
