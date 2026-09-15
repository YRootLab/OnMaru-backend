package com.yrootlab.onmaru.tourism.audio.mapping;

import com.yrootlab.onmaru.audio.sync.OdiiSourceStory;
import com.yrootlab.onmaru.tourism.audio.client.OdiiSourceItem;

public final class OdiiSourceItemMapper {

    public OdiiSourceStory toSourceStory(OdiiSourceItem item) {
        return new OdiiSourceStory(
                item.tid(),
                item.tlid(),
                item.stid(),
                item.stlid(),
                item.title(),
                item.audioTitle(),
                item.script(),
                item.audioUrl(),
                item.imageUrl(),
                item.playTime(),
                item.mapX(),
                item.mapY(),
                item.langCode(),
                item.createdTime(),
                item.modifiedTime()
        );
    }
}
