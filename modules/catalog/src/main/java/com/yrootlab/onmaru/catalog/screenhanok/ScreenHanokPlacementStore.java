package com.yrootlab.onmaru.catalog.screenhanok;

import java.util.List;

public interface ScreenHanokPlacementStore {

    void publish(List<ScreenHanokPlacement> placements);

    List<ScreenHanokPlacement> current();
}
