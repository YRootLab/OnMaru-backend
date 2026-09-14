package com.yrootlab.onmaru.catalog.editorial;

import java.time.YearMonth;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryMonthlyHanokEditionStore implements MonthlyHanokEditionStore {

    private final Map<YearMonth, MonthlyHanokEditionDraft> editions = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    @Override
    public void publish(MonthlyHanokEditionDraft draft) {
        editions.put(draft.month(), draft);
    }

    @Override
    public Optional<MonthlyHanokEditionDraft> find(YearMonth month) {
        if (unavailable) {
            throw new MonthlyHanokEditionUnavailableException();
        }
        return Optional.ofNullable(editions.get(month));
    }

    public void clear() {
        editions.clear();
        unavailable = false;
    }

    public void markUnavailable() {
        unavailable = true;
    }
}
