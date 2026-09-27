package com.yrootlab.onmaru.tourism.catalog.sync;

import com.yrootlab.onmaru.catalog.application.qualification.SourceRecord;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiPage;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;
import com.yrootlab.onmaru.tourism.catalog.mapping.TourApiCatalogSourceMapper;
import com.yrootlab.onmaru.tourism.catalog.mapping.TourApiLclsCategoryResolver;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntFunction;

public final class TourApiCatalogSnapshotSource {

    private final TourApiPageFetcher fetcher;
    private final TourApiUriBuilder uriBuilder;
    private final TourApiCatalogSourceMapper mapper = new TourApiCatalogSourceMapper();
    private final int pageSize;

    public TourApiCatalogSnapshotSource(TourApiHttpClient client, TourApiUriBuilder uriBuilder, int pageSize) {
        this(client::get, uriBuilder, pageSize);
    }

    public TourApiCatalogSnapshotSource(TourApiPageFetcher fetcher, TourApiUriBuilder uriBuilder, int pageSize) {
        this.fetcher = Objects.requireNonNull(fetcher);
        this.uriBuilder = Objects.requireNonNull(uriBuilder);
        if (pageSize < 1 || pageSize > 1000) throw new IllegalArgumentException("pageSize must be between 1 and 1000");
        this.pageSize = pageSize;
    }

    public SnapshotResult streamFullSnapshot(Consumer<List<SourceRecord>> pageConsumer) {
        Objects.requireNonNull(pageConsumer);
        TourApiLclsCategoryResolver resolver = TourApiLclsCategoryResolver.fromOfficialCodes(
                collect("lclsSystmCode2", page -> uriBuilder.classificationCodes(page, pageSize, true)));

        int pageNo = 1;
        int rawCount = 0;
        while (true) {
            TourApiPage page = fetch("areaBasedList2 page=" + pageNo,
                    uriBuilder.areaBasedList(pageNo, pageSize, Map.of("arrange", "C")));
            List<SourceRecord> records = page.items().stream()
                    .map(mapper::toSourceRecord)
                    .map(resolver::enrich)
                    .toList();
            if (!records.isEmpty()) pageConsumer.accept(records);
            rawCount += records.size();
            if (rawCount >= page.totalCount()) return new SnapshotResult(rawCount, page.totalCount());
            if (records.isEmpty()) {
                throw new IllegalStateException("TourAPI areaBasedList2 ended before totalCount: expected="
                        + page.totalCount() + ", actual=" + rawCount);
            }
            pageNo++;
        }
    }

    private List<SourceRecord> collect(String operation, IntFunction<URI> uriFactory) {
        var records = new ArrayList<SourceRecord>();
        int pageNo = 1;
        int observed = 0;
        while (true) {
            TourApiPage page = fetch(operation, uriFactory.apply(pageNo));
            page.items().stream().map(mapper::toSourceRecord).forEach(records::add);
            observed += page.items().size();
            if (observed >= page.totalCount()) return List.copyOf(records);
            if (page.items().isEmpty()) {
                throw new IllegalStateException("TourAPI " + operation + " ended before totalCount");
            }
            pageNo++;
        }
    }

    private TourApiPage fetch(String operation, URI uri) {
        try {
            TourApiParseResult result = fetcher.fetch(operation, uri);
            if (result.page() == null) {
                String kind = result.error() == null ? "UNKNOWN" : result.error().kind().name();
                String detail = result.error() == null ? "" : result.error().providerMessage();
                throw new IllegalStateException("TourAPI " + operation + " failed: " + kind
                        + (detail == null || detail.isBlank() ? "" : " (" + detail + ")"));
            }
            return result.page();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("TourAPI " + operation + " interrupted", exception);
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("TourAPI " + operation + " transport failed", exception);
        }
    }

    public record SnapshotResult(int rawCount, int providerTotalCount) {
    }
}
