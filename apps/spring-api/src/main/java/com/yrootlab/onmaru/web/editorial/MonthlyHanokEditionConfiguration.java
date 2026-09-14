package com.yrootlab.onmaru.web.editorial;

import com.yrootlab.onmaru.catalog.application.query.hanok.HanokSavedStateLookup;
import com.yrootlab.onmaru.catalog.application.query.hanok.InMemoryHanokListStore;
import com.yrootlab.onmaru.catalog.editorial.InMemoryMonthlyHanokEditionStore;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionDraft;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokEditionService;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokPlacementDraft;
import com.yrootlab.onmaru.catalog.editorial.MonthlyHanokSlot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.YearMonth;
import java.util.List;

@Configuration
class MonthlyHanokEditionConfiguration {

    @Bean
    InMemoryMonthlyHanokEditionStore monthlyHanokEditionStore() {
        var store = new InMemoryMonthlyHanokEditionStore();
        store.publish(new MonthlyHanokEditionDraft(
                YearMonth.of(2026, 9),
                "9월의 한옥 산책",
                "선선한 저녁에 걷기 좋은 한옥 장소를 모았습니다.",
                List.of(
                        new MonthlyHanokPlacementDraft(
                                MonthlyHanokSlot.HERO,
                                "대표 한옥 권역으로 첫 화면에서 소개합니다.",
                                "p-jeonju-hanok-village"),
                        new MonthlyHanokPlacementDraft(
                                MonthlyHanokSlot.CAFE,
                                "한옥 카페를 찾는 사용자를 위한 보조 placement입니다.",
                                "p-bukchon-hanok-cafe"))));
        return store;
    }

    @Bean
    MonthlyHanokEditionService monthlyHanokEditionService(
            InMemoryMonthlyHanokEditionStore monthlyHanokEditionStore,
            InMemoryHanokListStore hanokListStore,
            HanokSavedStateLookup savedStateLookup) {
        return new MonthlyHanokEditionService(monthlyHanokEditionStore, hanokListStore, savedStateLookup);
    }
}
