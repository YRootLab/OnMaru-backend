package com.yrootlab.onmaru.community.command.review;

import java.util.UUID;

@FunctionalInterface
public interface ReviewIdGenerator {

    UUID generate();
}
