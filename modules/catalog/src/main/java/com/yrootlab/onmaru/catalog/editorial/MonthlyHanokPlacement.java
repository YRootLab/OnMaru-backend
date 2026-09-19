package com.yrootlab.onmaru.catalog.editorial;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokCard;

public record MonthlyHanokPlacement(
        MonthlyHanokSlot slot,
        String reason,
        HanokCard place) {
}
