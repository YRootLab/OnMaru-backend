package com.yrootlab.onmaru.journey.savedjourney;

import com.yrootlab.onmaru.journey.actions.ResourceRef;

public interface ResourceAvailability {
    boolean isPublic(ResourceRef ref);
}
