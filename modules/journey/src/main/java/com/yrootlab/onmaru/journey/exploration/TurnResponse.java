package com.yrootlab.onmaru.journey.exploration;

import java.util.Map;

public record TurnResponse(
        String status,
        String outcome,
        String summary,
        Map<String, Object> board,
        Map<String, Object> proposal,
        Map<String, Object> clarification,
        Map<String, Object> error) {

    public static TurnResponse pending() {
        return new TurnResponse("PENDING", null, null, null, null, null, null);
    }

    public static TurnResponse completed(
            String outcome,
            String summary,
            Map<String, Object> board,
            Map<String, Object> proposal,
            Map<String, Object> clarification) {
        return new TurnResponse("COMPLETED", outcome, summary, board, proposal, clarification, null);
    }

    public static TurnResponse failed(Map<String, Object> error) {
        return new TurnResponse("FAILED", null, null, null, null, null, error);
    }

    public static TurnResponse cancelled() {
        return new TurnResponse("CANCELLED", null, null, null, null, null, null);
    }
}
