package com.yrootlab.onmaru.audio.sync;

import java.util.List;

public record OdiiSourcePage(List<OdiiSourceStory> stories, boolean lastPage) {

    public OdiiSourcePage {
        stories = List.copyOf(stories);
    }
}
