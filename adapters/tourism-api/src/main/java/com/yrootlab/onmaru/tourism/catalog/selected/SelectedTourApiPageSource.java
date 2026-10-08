package com.yrootlab.onmaru.tourism.catalog.selected;

import com.yrootlab.onmaru.catalog.application.sourcefetch.SelectedSourceFetch;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import com.yrootlab.onmaru.tourism.catalog.mapping.TourApiCatalogSourceMapper;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiPageFetcher;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

/** HTTP and envelope handling stay in the TourAPI adapter; the fetch policy stays in catalog. */
public final class SelectedTourApiPageSource implements SelectedSourceFetch.PageSource {
    private final TourApiPageFetcher fetcher;
    private final TourApiUriBuilder uris;
    private final TourApiCatalogSourceMapper mapper = new TourApiCatalogSourceMapper();

    public SelectedTourApiPageSource(TourApiPageFetcher fetcher, TourApiUriBuilder uris) {
        this.fetcher = Objects.requireNonNull(fetcher);
        this.uris = Objects.requireNonNull(uris);
    }

    @Override
    public SelectedSourceFetch.Page fetch(SelectedSourceFetch.Query query, int pageNumber, int pageSize) {
        URI uri = switch (query.operation()) {
            case "areaBasedList2" -> uris.areaBasedList(pageNumber, pageSize,
                    Map.of("lclsSystm3", query.filter(), "arrange", "C"));
            case "searchKeyword2" -> uris.searchKeyword(query.filter(), pageNumber, pageSize);
            default -> throw new IllegalArgumentException("Unsupported operation: " + query.operation());
        };
        try {
            TourApiParseResult result = fetcher.fetch(query.operation(), uri);
            if (result == null || result.page() == null) {
                String kind = result == null || result.error() == null ? "UNKNOWN" : result.error().kind().name();
                throw new IllegalStateException("TourAPI page failed: " + query + " page=" + pageNumber + " kind=" + kind);
            }
            TourApiPage page = result.page();
            if (!query.operation().equals(page.operation())) {
                throw new IllegalStateException("TourAPI operation mismatch: " + query);
            }
            return new SelectedSourceFetch.Page(page.pageNo(), page.numOfRows(), page.totalCount(),
                    page.items().stream().map(mapper::toSourceRecord).toList());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TourAPI page interrupted: " + query, exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("TourAPI transport failed: " + query, exception);
        }
    }
}
