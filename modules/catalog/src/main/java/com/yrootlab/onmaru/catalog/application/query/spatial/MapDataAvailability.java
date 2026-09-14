package com.yrootlab.onmaru.catalog.application.query.spatial;

public record MapDataAvailability(
        MapCoverageStatus place,
        MapCoverageStatus observation,
        MapCoverageStatus odii) {
}
