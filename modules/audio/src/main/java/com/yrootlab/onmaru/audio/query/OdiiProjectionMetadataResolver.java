package com.yrootlab.onmaru.audio.query;

import com.yrootlab.onmaru.audio.sync.OdiiSpotVersion;

@FunctionalInterface
public interface OdiiProjectionMetadataResolver {

    OdiiProjectionMetadata resolve(OdiiSpotVersion spot);
}
