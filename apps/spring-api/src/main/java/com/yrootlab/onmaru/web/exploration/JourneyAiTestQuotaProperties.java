package com.yrootlab.onmaru.web.exploration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@ConfigurationProperties("onmaru.journey.test-quota")
public final class JourneyAiTestQuotaProperties {

    private Instant start = Instant.parse("2026-09-30T15:00:00Z");
    private Instant end = Instant.parse("2026-10-31T15:00:00Z");
    private int limit = 2;
    private Set<UUID> exemptMemberIds = Set.of();

    public Instant getStart() { return start; }
    public void setStart(Instant start) { this.start = start; }
    public Instant getEnd() { return end; }
    public void setEnd(Instant end) { this.end = end; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public Set<UUID> getExemptMemberIds() { return exemptMemberIds; }
    public void setExemptMemberIds(Set<UUID> exemptMemberIds) {
        this.exemptMemberIds = exemptMemberIds == null ? Set.of() : Set.copyOf(exemptMemberIds);
    }

    public void validate() {
        if (start == null || end == null || !end.isAfter(start) || limit <= 0) {
            throw new IllegalArgumentException("Journey AI test quota requires a positive limit and ordered period");
        }
    }
}
