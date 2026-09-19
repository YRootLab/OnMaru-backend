package com.yrootlab.onmaru.catalog.editorial;

import java.time.YearMonth;
import java.util.Optional;

public interface MonthlyHanokEditionStore {

    void publish(MonthlyHanokEditionDraft draft);

    Optional<MonthlyHanokEditionDraft> find(YearMonth month);
}
