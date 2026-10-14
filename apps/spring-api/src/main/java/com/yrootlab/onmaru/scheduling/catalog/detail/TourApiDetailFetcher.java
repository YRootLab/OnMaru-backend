package com.yrootlab.onmaru.scheduling.catalog.detail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiHttpClient;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiOutcomeKind;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiParseResult;
import com.yrootlab.onmaru.tourism.catalog.client.TourApiUriBuilder;

import java.io.IOException;
import java.net.URI;

/**
 * 한 장소의 상세 두 응답(detailCommon2, detailIntro2)을 호출한다. detailInfo2는 일괄 수집 대상이 아니다.
 * 기능별 일일 한도는 호출 기능마다 따로 적용되므로, 장소당 기능별 호출은 각 1회다.
 */
public final class TourApiDetailFetcher implements DetailFetcher {

    private final TourApiHttpClient client;
    private final TourApiUriBuilder uris;

    public TourApiDetailFetcher(TourApiHttpClient client, TourApiUriBuilder uris) {
        this.client = client;
        this.uris = uris;
    }

    @Override
    public DetailFetchResult fetch(DetailCollectionTarget target) {
        try {
            Part common = get("detailCommon2", uris.detailCommon(target.contentId()), false);
            if (common.failure() != null) {
                return common.failure();
            }
            Part intro = get("detailIntro2",
                    uris.detailIntro(target.contentId(), target.contentTypeId()), true);
            if (intro.failure() != null) {
                return intro.failure();
            }
            // detailInfo2(세부 항목)는 일괄 수집하지 않는다. 사용자 조회 시 필요할 때만 불러온다.
            return DetailFetchResult.ok(new DetailPayloads(common.node(), intro.node(), null));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return DetailFetchResult.failed("INTERRUPTED", true);
        }
    }

    /** multiItem이 false이면 응답 항목이 정확히 1건이어야 한다. true이면 0건 이상을 배열로 모은다. */
    private Part get(String operation, URI uri, boolean multiItem) throws InterruptedException {
        TourApiParseResult response;
        try {
            response = client.get(operation, uri);
        } catch (IOException exception) {
            return Part.failed(DetailFetchResult.failed("TRANSPORT_ERROR", true));
        }
        if (response.kind() != TourApiOutcomeKind.SUCCESS || response.page() == null) {
            return Part.failed(mapFailure(response.kind()));
        }
        var page = response.page();
        if (!multiItem) {
            if (page.items().size() != 1) {
                return Part.failed(DetailFetchResult.failed("DETAIL_NOT_SINGLE", false));
            }
            return Part.ok(page.items().getFirst().fields());
        }
        if (!page.isLastPage()) {
            return Part.failed(DetailFetchResult.failed("DETAIL_MULTI_PAGE", false));
        }
        ArrayNode rows = JsonNodeFactory.instance.arrayNode();
        page.items().forEach(item -> rows.add(item.fields()));
        return Part.ok(rows);
    }

    private static DetailFetchResult mapFailure(TourApiOutcomeKind kind) {
        if (kind == null) {
            return DetailFetchResult.failed("UNKNOWN", true);
        }
        return switch (kind) {
            case RATE_LIMITED, AUTH_OR_PERMISSION_ERROR, UPSTREAM_TIMEOUT_OR_GATEWAY_ERROR ->
                    DetailFetchResult.failed(kind.name(), true);
            case PROVIDER_PARAMETER_ERROR, SCHEMA_DRIFT -> DetailFetchResult.failed(kind.name(), false);
            case SUCCESS -> DetailFetchResult.failed("UNEXPECTED_SUCCESS_WITHOUT_PAGE", false);
        };
    }

    private record Part(JsonNode node, DetailFetchResult failure) {
        static Part ok(JsonNode node) {
            return new Part(node, null);
        }

        static Part failed(DetailFetchResult failure) {
            return new Part(null, failure);
        }
    }
}
