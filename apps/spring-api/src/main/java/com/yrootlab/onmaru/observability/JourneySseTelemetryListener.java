package com.yrootlab.onmaru.observability;

import com.yrootlab.onmaru.journey.events.JourneyRunEventType;
import com.yrootlab.onmaru.web.exploration.JourneySseTelemetryEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
class JourneySseTelemetryListener {

    private final TelemetrySink telemetrySink;

    JourneySseTelemetryListener(TelemetrySink telemetrySink) {
        this.telemetrySink = telemetrySink;
    }

    @EventListener
    void record(JourneySseTelemetryEvent event) {
        if ("journey.sse.auth_closed".equals(event.name())) {
            telemetrySink.record(new TelemetryEvent(event.name(), Map.of("reason", event.reason())));
            return;
        }
        var attributes = new LinkedHashMap<String, String>();
        attributes.put("run.id", event.runId().toString());
        attributes.put("last.event.id", event.lastEventId() == null ? "" : event.lastEventId().toString());
        attributes.put("event.count", Integer.toString(event.replay().events().size()));
        attributes.put("reset", Boolean.toString(event.replay().resetRequired()));
        attributes.put("terminal.close", Boolean.toString(event.replay().events().stream()
                .anyMatch(frame -> frame.closeAfterSend() && frame.type() == JourneyRunEventType.TERMINAL)));
        telemetrySink.record(new TelemetryEvent(event.name(), attributes));
        if (event.replay().resetRequired()) {
            telemetrySink.record(new TelemetryEvent("journey.sse.reset", Map.of(
                    "run.id", event.runId().toString(),
                    "last.event.id", event.lastEventId() == null ? "" : event.lastEventId().toString())));
        }
    }
}
