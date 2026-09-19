package com.yrootlab.onmaru.community.moderation;

import java.util.UUID;

@FunctionalInterface
public interface ReviewReportIdGenerator {

    UUID generate();
}
