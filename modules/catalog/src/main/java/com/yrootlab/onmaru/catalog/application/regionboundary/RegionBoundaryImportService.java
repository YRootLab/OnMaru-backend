package com.yrootlab.onmaru.catalog.application.regionboundary;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class RegionBoundaryImportService {

    private static final Pattern SHA_256_PATTERN = Pattern.compile("[0-9a-f]{64}");

    private final RegionBoundaryStore store;

    public RegionBoundaryImportService(RegionBoundaryStore store) {
        this.store = store;
    }

    public RegionBoundaryImportResult importAndActivate(RegionBoundaryImportCommand command) {
        List<RegionBoundaryQuarantine> quarantine = new ArrayList<>();
        if (!isQualifiedSource(command.source())) {
            return new RegionBoundaryImportResult(
                    RegionBoundaryImportStatus.REJECTED,
                    command.source().revisionId(),
                    0,
                    List.of(new RegionBoundaryQuarantine(command.source().revisionId(), "SOURCE_NOT_QUALIFIED", "source manifest is incomplete"))
            );
        }

        Set<String> acceptedSidoCodes = new HashSet<>();
        for (RegionBoundaryCandidate candidate : command.boundaries()) {
            if (candidate.level() == RegionBoundaryLevel.SIDO && isCandidateShapeValid(candidate)) {
                acceptedSidoCodes.add(candidate.regionCode());
            }
        }

        List<RegionBoundaryProjection> accepted = new ArrayList<>();
        for (RegionBoundaryCandidate candidate : command.boundaries()) {
            RegionBoundaryQuarantine rejected = validate(candidate, acceptedSidoCodes);
            if (rejected != null) {
                quarantine.add(rejected);
            } else {
                accepted.add(new RegionBoundaryProjection(
                        command.source().revisionId(),
                        candidate.regionCode().strip(),
                        blankToNull(candidate.parentRegionCode()),
                        candidate.name().strip(),
                        candidate.level(),
                        candidate.geometry()
                ));
            }
        }

        if (accepted.isEmpty()) {
            return new RegionBoundaryImportResult(
                    RegionBoundaryImportStatus.REJECTED,
                    command.source().revisionId(),
                    0,
                    quarantine
            );
        }

        store.activate(new RegionBoundaryRevision(command.source().revisionId(), command.source(), accepted));
        RegionBoundaryImportStatus status = quarantine.isEmpty()
                ? RegionBoundaryImportStatus.ACTIVATED
                : RegionBoundaryImportStatus.ACTIVATED_WITH_QUARANTINE;
        return new RegionBoundaryImportResult(status, command.source().revisionId(), accepted.size(), quarantine);
    }

    private RegionBoundaryQuarantine validate(RegionBoundaryCandidate candidate, Set<String> acceptedSidoCodes) {
        if (isBlank(candidate.regionCode()) || isBlank(candidate.name()) || candidate.level() == null) {
            return quarantine(candidate, "MISSING_REQUIRED_FIELD");
        }
        if (!isCandidateShapeValid(candidate)) {
            return quarantine(candidate, "INVALID_GEOMETRY");
        }
        if (candidate.level() == RegionBoundaryLevel.SIDO && candidate.parentRegionCode() != null) {
            return quarantine(candidate, "INVALID_PARENT_REGION");
        }
        if (candidate.level() == RegionBoundaryLevel.SIGUNGU && !acceptedSidoCodes.contains(candidate.parentRegionCode())) {
            return quarantine(candidate, "PARENT_REGION_NOT_FOUND");
        }
        return null;
    }

    private boolean isCandidateShapeValid(RegionBoundaryCandidate candidate) {
        return candidate.geometry() != null && candidate.geometry().isValid();
    }

    private boolean isQualifiedSource(RegionBoundarySourceManifest source) {
        return source != null
                && !isBlank(source.revisionId())
                && isHttpUrl(source.datasetUrl())
                && !isBlank(source.license())
                && !isBlank(source.attribution())
                && source.observedAt() != null
                && source.revisionHash() != null
                && SHA_256_PATTERN.matcher(source.revisionHash().toLowerCase(Locale.ROOT)).matches();
    }

    private boolean isHttpUrl(String value) {
        if (isBlank(value)) {
            return false;
        }
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme());
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private RegionBoundaryQuarantine quarantine(RegionBoundaryCandidate candidate, String errorCode) {
        String recordKey = candidate == null || isBlank(candidate.regionCode()) ? "<unknown>" : candidate.regionCode().strip();
        return new RegionBoundaryQuarantine(recordKey, errorCode, "region boundary candidate rejected");
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.strip();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
