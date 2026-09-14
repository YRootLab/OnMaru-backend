package com.yrootlab.onmaru.catalog.editorial;

import java.util.List;

public record MonthlyHanokEdition(
        String schemaVersion,
        String month,
        String title,
        String subtitle,
        List<MonthlyHanokPlacement> placements) {
}
