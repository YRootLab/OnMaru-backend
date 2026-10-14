package com.yrootlab.onmaru.scheduling.catalog.detail;

/**
 * 한 장소의 상세 호출 결과다. 세 응답이 모두 성공해야 OK다.
 * stopRun이 true이면 이번 실행의 남은 호출을 중단한다(한도 초과, 인증 실패, 장애 등).
 */
public record DetailFetchResult(Status status, DetailPayloads payloads, String errorCode, boolean stopRun) {

    public enum Status { OK, FAILED }

    public static DetailFetchResult ok(DetailPayloads payloads) {
        return new DetailFetchResult(Status.OK, payloads, null, false);
    }

    public static DetailFetchResult failed(String errorCode, boolean stopRun) {
        return new DetailFetchResult(Status.FAILED, null, errorCode, stopRun);
    }
}
