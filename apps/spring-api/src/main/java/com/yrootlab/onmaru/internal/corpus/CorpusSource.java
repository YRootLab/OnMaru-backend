package com.yrootlab.onmaru.internal.corpus;

import java.util.List;

public interface CorpusSource {

    List<CorpusChunk> activeChunks();
}
