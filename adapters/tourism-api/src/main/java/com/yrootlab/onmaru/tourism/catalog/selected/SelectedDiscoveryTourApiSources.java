package com.yrootlab.onmaru.tourism.catalog.selected;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.catalog.application.selectedsync.SelectedDiscoverySync;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import com.yrootlab.onmaru.tourism.catalog.mapping.TourApiCatalogSourceMapper;
import com.yrootlab.onmaru.tourism.catalog.sync.TourApiPageFetcher;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Freezes the official taxonomy per run and fetches details only for operator-approved IDs. */
public final class SelectedDiscoveryTourApiSources implements SelectedDiscoverySync.TaxonomyRegistry,
        SelectedDiscoverySync.DetailSource {
    private final TourApiPageFetcher fetcher;
    private final TourApiUriBuilder uris;
    private final TourApiCatalogSourceMapper mapper = new TourApiCatalogSourceMapper();

    public SelectedDiscoveryTourApiSources(TourApiPageFetcher fetcher, TourApiUriBuilder uris) {
        this.fetcher = Objects.requireNonNull(fetcher);
        this.uris = Objects.requireNonNull(uris);
    }

    @Override
    public Set<String> officialCodes() {
        Set<String> codes = new LinkedHashSet<>();
        int pageNo = 1;
        int received = 0;
        int total = -1;
        while (true) {
            TourApiPage page = get("lclsSystmCode2", uris.classificationCodes(pageNo, 1000, true));
            if (page.pageNo() != pageNo || page.numOfRows() != 1000 || page.totalCount() < 1
                    || total >= 0 && total != page.totalCount()) throw new IllegalStateException("TourAPI taxonomy page drift");
            if (total < 0) total = page.totalCount();
            int expected = Math.min(1000, total - received);
            if (page.items().size() != expected) throw new IllegalStateException("TourAPI taxonomy page incomplete");
            page.items().forEach(item -> {
                SourceRecord row = mapper.toSourceRecord(item);
                String code = row.field("lclsSystm3Cd");
                if (code != null && !code.isBlank()) codes.add(code);
            });
            received += page.items().size();
            if (received == total) return Set.copyOf(codes);
            pageNo++;
        }
    }

    @Override
    public Map<String, String> fetch(SourceRecord row) {
        String contentId = row.field("contentid");
        TourApiPage page = get("detailCommon2", uris.detailCommon(contentId));
        if (page.pageNo() != 1 || page.totalCount() != 1 || page.items().size() != 1) {
            throw new IllegalStateException("TourAPI detail missing for contentId=" + contentId);
        }
        SourceRecord detail = mapper.toSourceRecord(page.items().getFirst());
        if (!contentId.equals(detail.field("contentid"))) throw new IllegalStateException("TourAPI detail identity mismatch");
        return Map.copyOf(new LinkedHashMap<>(detail.fields()));
    }

    private TourApiPage get(String operation, URI uri) {
        try {
            TourApiParseResult response = fetcher.fetch(operation, uri);
            if (response == null || response.page() == null) {
                String code = response == null || response.error() == null ? "UNKNOWN" : response.error().kind().name();
                throw new IllegalStateException("TourAPI " + operation + " failed: " + code);
            }
            if (!operation.equals(response.page().operation())) throw new IllegalStateException("TourAPI operation mismatch");
            return response.page();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TourAPI " + operation + " interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("TourAPI " + operation + " transport failure", exception);
        }
    }
}
