package com.yrootlab.onmaru.catalog.editorial;

import java.time.YearMonth;
import java.util.List;

public record MonthlyHanokEditionDraft(
        YearMonth month,
        String title,
        String subtitle,
        List<MonthlyHanokPlacementDraft> placements) {

    public MonthlyHanokEditionDraft {
        placements = List.copyOf(placements);
    }
}
