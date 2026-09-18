package com.yrootlab.onmaru.catalog.screenhanok;

import java.util.List;

/**
 * Hexagonal port for the AI research step (ADR-0010). The only implementation calls FastAPI's
 * internal contract; Spring/Java must never call an LLM provider SDK directly.
 */
public interface ScreenHanokResearchPort {

    List<ScreenHanokMatch> research(List<ScreenHanokCandidate> candidates);
}
