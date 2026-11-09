package com.yrootlab.onmaru.mapcollab.domain.exception;

import com.yrootlab.onmaru.mapcollab.domain.Pin;

import java.util.LinkedHashMap;
import java.util.Map;

public final class PinAlreadyExistsException extends MapCollabException {

    private final Pin existingPin;

    public PinAlreadyExistsException(Pin existingPin) {
        super("PIN_ALREADY_EXISTS", 409, "place already pinned in this room", buildDetails(existingPin));
        this.existingPin = existingPin;
    }

    public Pin getExistingPin() {
        return existingPin;
    }

    private static Map<String, Object> buildDetails(Pin pin) {
        if (pin == null) {
            return Map.of();
        }
        var pinMap = new LinkedHashMap<String, Object>();
        pinMap.put("pinId", pin.pinId().toString());
        pinMap.put("placeId", pin.placeId());
        pinMap.put("placeName", pin.placeName());
        pinMap.put("lat", pin.lat());
        pinMap.put("lng", pin.lng());
        pinMap.put("addedBy", pin.addedBy().toString());
        pinMap.put("addedAt", pin.addedAt().toString());
        pinMap.put("order", pin.order());

        var details = new LinkedHashMap<String, Object>();
        details.put("existingPin", pinMap);
        return details;
    }
}
