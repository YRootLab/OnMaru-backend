package com.yrootlab.onmaru.web.tour;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yrootlab.onmaru.config.secrets.SecretProvider;
import com.yrootlab.onmaru.tourism.catalog.TourApiClientConfiguration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

@RestController
final class TourApiProxyController {

    private static final Map<String, String> OPERATIONS = Map.ofEntries(
            Map.entry("searchKeyword1", "searchKeyword2"),
            Map.entry("searchKeyword2", "searchKeyword2"),
            Map.entry("areaBasedList1", "areaBasedList2"),
            Map.entry("areaBasedList2", "areaBasedList2"),
            Map.entry("locationBasedList1", "locationBasedList2"),
            Map.entry("locationBasedList2", "locationBasedList2"),
            Map.entry("detailCommon1", "detailCommon2"),
            Map.entry("detailCommon2", "detailCommon2"),
            Map.entry("detailIntro1", "detailIntro2"),
            Map.entry("detailIntro2", "detailIntro2"),
            Map.entry("detailInfo1", "detailInfo2"),
            Map.entry("detailInfo2", "detailInfo2"),
            Map.entry("detailImage1", "detailImage2"),
            Map.entry("detailImage2", "detailImage2"),
            Map.entry("searchFestival1", "searchFestival2"),
            Map.entry("searchFestival2", "searchFestival2"));

    private final ObjectMapper objectMapper;
    private final SecretProvider secrets;
    private final TourApiClientConfiguration.TourApiSyncSettings settings;
    private final HttpClient client;

    TourApiProxyController(
            ObjectMapper objectMapper,
            SecretProvider secrets,
            TourApiClientConfiguration.TourApiSyncSettings settings) {
        this.objectMapper = objectMapper;
        this.secrets = secrets;
        this.settings = settings;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @GetMapping(value = "/api/tour/{operation}", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<?> proxy(
            @PathVariable String operation,
            @RequestParam Map<String, String> parameters) {
        String providerOperation = OPERATIONS.get(operation);
        if (providerOperation == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "error", "Unsupported TourAPI operation",
                    "operation", operation));
        }
        try {
            URI uri = buildUri(providerOperation, parameters);
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(10))
                            .header("Accept", "application/json")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            // Validate that body is valid JSON if response is 2xx, then return raw JSON string
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                // Ensure it's valid JSON to avoid returning HTML error pages as JSON
                try {
                    objectMapper.readTree(response.body());
                } catch (Exception ignored) {
                    // fall through
                }
            }
            return ResponseEntity.status(response.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", "TourAPI request interrupted"));
        } catch (IOException | IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "TourAPI request failed"));
        }
    }

    private URI buildUri(String operation, Map<String, String> input) {
        StringJoiner query = new StringJoiner("&");
        add(query, "MobileOS", "ETC");
        add(query, "MobileApp", settings.mobileApp());
        add(query, "_type", "json");
        add(query, "serviceKey", secrets.get("tourapi.service-key").current());
        input.forEach((key, value) -> {
            if (!Set.of("serviceKey", "MobileOS", "MobileApp", "_type").contains(key)
                    && value != null && !value.isBlank()) {
                String normalizedValue = value;
                if ("contentId".equals(key)) {
                    normalizedValue = normalizedValue.replace("p-tourapi-", "");
                }
                add(query, key, normalizedValue);
            }
        });
        return URI.create(settings.baseUri().toString().replaceAll("/$", "") + "/" + operation + "?" + query);
    }

    private void add(StringJoiner query, String key, String value) {
        query.add(encode(key) + "=" + encode(value));
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
