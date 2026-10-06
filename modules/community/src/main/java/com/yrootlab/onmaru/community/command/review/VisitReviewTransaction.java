package com.yrootlab.onmaru.community.command.review;

import java.util.function.Supplier;

public interface VisitReviewTransaction {

    <T> T execute(Supplier<T> operation);
}
