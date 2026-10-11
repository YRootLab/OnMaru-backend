package com.yrootlab.onmaru.admin.pipeline;

public record AdminPipelineProgress(long completed, Long total, Double percent, String currentStage) {
    public static AdminPipelineProgress of(long completed, Long total, String currentStage) {
        Double percent = total == null || total == 0 ? null : Math.round(completed * 1000.0 / total) / 10.0;
        return new AdminPipelineProgress(completed, total, percent, currentStage);
    }
}
