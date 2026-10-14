package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiOutcomeKind;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;

import java.io.IOException;

/** 공통 상세(detailCommon2) 한 건을 호출해 수집 결과로 바꾼다. 기능별 일일 한도는 이 호출 하나에만 적용된다. */
public final class TourApiDetailFetcher implements DetailFetcher {

    private static final String OPERATION = "detailCommon2";

    private final TourApiHttpClient client;
    private final TourApiUriBuilder uris;

    public TourApiDetailFetcher(TourApiHttpClient client, TourApiUriBuilder uris) {
        this.client = client;
        this.uris = uris;
    }

    @Override
    public DetailFetchResult fetch(String contentId) {
        try {
            TourApiParseResult response = client.get(OPERATION, uris.detailCommon(contentId));
            if (response.kind() == TourApiOutcomeKind.SUCCESS && response.page() != null) {
                var items = response.page().items();
                if (items.size() != 1) {
                    return DetailFetchResult.failed("DETAIL_NOT_SINGLE", false);
                }
                return DetailFetchResult.ok(items.getFirst().fields());
            }
            return mapFailure(response.kind());
        } catch (IOException exception) {
            return DetailFetchResult.failed("TRANSPORT_ERROR", true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DetailFetchResult.failed("INTERRUPTED", true);
        }
    }

    private static DetailFetchResult mapFailure(TourApiOutcomeKind kind) {
        if (kind == null) {
            return DetailFetchResult.failed("UNKNOWN", true);
        }
        return switch (kind) {
            case RATE_LIMITED -> DetailFetchResult.failed(kind.name(), true);
            case AUTH_OR_PERMISSION_ERROR -> DetailFetchResult.failed(kind.name(), true);
            case UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR -> DetailFetchResult.failed(kind.name(), true);
            case PROVIDER_PARAMETER_ERROR, SCHEMA_DRIFT -> DetailFetchResult.failed(kind.name(), false);
            case SUCCESS -> DetailFetchResult.failed("UNEXPECTED_SUCCESS_WITHOUT_PAGE", false);
        };
    }
}
