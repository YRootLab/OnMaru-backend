package com.yrootlab.onmaru.journey.savedjourney;

public final class SavedJourneyInputInvalidException extends RuntimeException {
    public SavedJourneyInputInvalidException(String field) {
        super(field);
    }
}
