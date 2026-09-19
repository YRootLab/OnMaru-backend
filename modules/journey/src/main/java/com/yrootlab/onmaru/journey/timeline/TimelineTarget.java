package com.yrootlab.onmaru.journey.timeline;

public sealed interface TimelineTarget {

    record PlaceTimelineTarget(String placeId) implements TimelineTarget {
        public String type() {
            return "PLACE";
        }
    }

    record OdiiStoryTimelineTarget(String storyId, String placeId) implements TimelineTarget {
        public String type() {
            return "ODII_STORY";
        }
    }

    record SavedJourneyTimelineTarget(String savedJourneyId) implements TimelineTarget {
        public String type() {
            return "SAVED_JOURNEY";
        }
    }

    record VisitReviewTimelineTarget(String reviewId, String placeId) implements TimelineTarget {
        public String type() {
            return "VISIT_REVIEW";
        }
    }
}
