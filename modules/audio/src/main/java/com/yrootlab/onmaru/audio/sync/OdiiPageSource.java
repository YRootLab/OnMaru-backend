package com.yrootlab.onmaru.audio.sync;

public interface OdiiPageSource {

    OdiiSourcePage fetch(String language, int page);
}
