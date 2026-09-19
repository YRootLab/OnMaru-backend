package com.yrootlab.onmaru.community.moderation;

public record ReviewReportReceipt(String schemaVersion, String reportId, ReviewReportStatus status) {

    public static ReviewReportReceipt from(ReviewReport report) {
        return new ReviewReportReceipt("1.2", report.reportId().toString(), report.status());
    }
}
