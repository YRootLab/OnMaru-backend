package com.yrootlab.onmaru.internal.corpus;

import com.yrootlab.onmaru.audio.sync.AudioRevisionStore;
import com.yrootlab.onmaru.audio.sync.AudioStatus;
import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;
import com.yrootlab.onmaru.audio.sync.OdiiStoryVersion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OdiiAudioCorpusSource implements CorpusSource {

    private static final String DATASET = "odii-audio";

    private final AudioRevisionStore revisionStore;

    public OdiiAudioCorpusSource(AudioRevisionStore revisionStore) {
        this.revisionStore = revisionStore;
    }

    @Override
    public List<CorpusChunk> activeChunks() {
        var active = revisionStore.activePublishedRevision(DATASET);
        var revision = active.revisionId().toString();
        var chunks = new ArrayList<CorpusChunk>();
        active.snapshot().spots().forEach(spot -> chunks.add(spotChunk(revision, spot)));
        active.snapshot().stories().forEach(story -> chunks.add(storyChunk(revision, story)));
        return chunks;
    }

    private CorpusChunk spotChunk(String revision, OdiiSpotVersion spot) {
        var identity = spot.identity();
        String sourceRef = identity.provider() + ":spot:" + identity.tid() + ":" + identity.tlid();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", "odii_spot");
        payload.put("sourceRef", sourceRef);
        payload.put("title", spot.title());
        payload.put("longitude", spot.longitude());
        payload.put("latitude", spot.latitude());
        payload.put("sourceModifiedAt", spot.sourceModifiedAt().toString());
        payload.put("status", spot.status().name());
        return chunk(revision, sourceRef, spot.status() == AudioStatus.DELETED, payload);
    }

    private CorpusChunk storyChunk(String revision, OdiiStoryVersion story) {
        var identity = story.identity();
        String sourceRef = identity.provider() + ":story:" + identity.langCode()
                + ":" + identity.stid() + ":" + identity.stlid();
        var payload = new LinkedHashMap<String, Object>();
        payload.put("kind", "odii_story");
        payload.put("sourceRef", sourceRef);
        payload.put("title", story.title());
        payload.put("script", story.script());
        payload.put("audioUrl", story.audioUrl());
        payload.put("imageUrl", story.imageUrl());
        payload.put("durationSeconds", story.durationSeconds());
        payload.put("sourceModifiedAt", story.sourceModifiedAt().toString());
        payload.put("status", story.status().name());
        return chunk(revision, sourceRef, story.status() == AudioStatus.DELETED, payload);
    }

    private CorpusChunk chunk(
            String revision,
            String sourceRef,
            boolean tombstone,
            Map<String, Object> payload
    ) {
        String chunkId = revision + ":" + sourceRef;
        return new CorpusChunk(
                "internal.corpus.v1",
                chunkId,
                revision,
                sourceRef,
                tombstone,
                CorpusHashes.chunkHash(payload),
                payload
        );
    }
}
