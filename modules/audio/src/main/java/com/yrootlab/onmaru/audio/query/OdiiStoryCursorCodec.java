package com.yrootlab.onmaru.audio.query;

public interface OdiiStoryCursorCodec {

    String encode(OdiiStoryCursor cursor);

    OdiiStoryCursor decode(String cursor);
}
