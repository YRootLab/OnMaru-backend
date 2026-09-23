package com.yrootlab.onmaru.tourism.audio.sync;

import com.yrootlab.onmaru.audio.sync.OdiiPageSource;
import com.yrootlab.onmaru.audio.sync.OdiiSourceException;
import com.yrootlab.onmaru.audio.sync.OdiiSourcePage;
import com.yrootlab.onmaru.tourism.audio.client.OdiiClientException;
import com.yrootlab.onmaru.tourism.audio.client.OdiiHttpClient;
import com.yrootlab.onmaru.tourism.audio.client.OdiiUriBuilder;
import com.yrootlab.onmaru.tourism.audio.mapping.OdiiSourceItemMapper;

public final class OdiiStorySearchPageSource implements OdiiPageSource {

    private static final String OPERATION = "storySearchList";

    private final OdiiHttpClient client;
    private final OdiiUriBuilder uriBuilder;
    private final OdiiSourceItemMapper mapper;
    private final int pageSize;

    public OdiiStorySearchPageSource(
            OdiiHttpClient client,
            OdiiUriBuilder uriBuilder,
            OdiiSourceItemMapper mapper,
            int pageSize
    ) {
        if (pageSize < 1) {
            throw new IllegalArgumentException("pageSize must be positive");
        }
        this.client = client;
        this.uriBuilder = uriBuilder;
        this.mapper = mapper;
        this.pageSize = pageSize;
    }

    @Override
    public OdiiSourcePage fetch(String language, String keyword, int page) {
        if (language == null || language.isBlank() || keyword == null || keyword.isBlank()) {
            throw new IllegalArgumentException("language and keyword must not be blank");
        }
        try {
            var providerPage = client.get(
                    OPERATION,
                    uriBuilder.storySearch(language, keyword, page, pageSize)
            );
            if (providerPage.page() != page) {
                throw new OdiiSourceException("ODII_PAGE_DRIFT");
            }
            return new OdiiSourcePage(
                    providerPage.items().stream().map(mapper::toSourceStory).toList(),
                    providerPage.lastPage()
            );
        } catch (OdiiClientException exception) {
            throw new OdiiSourceException(exception.getMessage(), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new OdiiSourceException("ODII_FETCH_INTERRUPTED", exception);
        }
    }
}
