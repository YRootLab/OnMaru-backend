package com.yrootlab.onmaru.journey.exploration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public interface ExplorationRegionCatalog {

    Optional<String> resolveRegionCode(String normalizedQuery);

    static ExplorationRegionCatalog empty() {
        return normalizedQuery -> Optional.empty();
    }

    static ExplorationRegionCatalog defaultFallback() {
        return fromEntries(List.of(
                new RegionEntry("kr-45-jeonju", "전주시", "SIGUNGU", "전북특별자치도"),
                new RegionEntry("kr-11-seoul", "서울특별시", "SIDO"),
                new RegionEntry("kr-47-gyeongju", "경주시", "SIGUNGU", "경상북도"),
                new RegionEntry("kr-47-andong", "안동시", "SIGUNGU", "경상북도"),
                new RegionEntry("kr-26-busan", "부산광역시", "SIDO")));
    }

    static ExplorationRegionCatalog fromEntries(List<RegionEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return empty();
        }
        return new DynamicExplorationRegionCatalog(entries);
    }

    record RegionEntry(String code, String name, String level, String parentName) {
        public RegionEntry(String code, String name, String level) {
            this(code, name, level, null);
        }

        public RegionEntry {
            code = Objects.requireNonNull(code, "code").trim();
            name = Objects.requireNonNull(name, "name").trim();
            level = level == null ? "" : level.trim().toUpperCase();
            parentName = parentName == null ? "" : parentName.trim();
        }
    }

    final class DynamicExplorationRegionCatalog implements ExplorationRegionCatalog {

        private static final Map<String, List<String>> PROVINCE_ALIASES = Map.ofEntries(
                Map.entry("충청북도", List.of("충청북도", "충북")),
                Map.entry("충청남도", List.of("충청남도", "충남")),
                Map.entry("전라북도", List.of("전라북도", "전북", "전북특별자치도")),
                Map.entry("전북특별자치도", List.of("전북특별자치도", "전라북도", "전북")),
                Map.entry("전라남도", List.of("전라남도", "전남")),
                Map.entry("경상북도", List.of("경상북도", "경북")),
                Map.entry("경상남도", List.of("경상남도", "경남")),
                Map.entry("강원특별자치도", List.of("강원특별자치도", "강원도", "강원")),
                Map.entry("강원도", List.of("강원도", "강원", "강원특별자치도")),
                Map.entry("제주특별자치도", List.of("제주특별자치도", "제주도", "제주")),
                Map.entry("세종특별자치시", List.of("세종특별자치시", "세종시", "세종")),
                Map.entry("전남광주통합특별시", List.of("전남광주통합특별시", "전라남도", "전남", "광주광역시", "광주시", "광주")));

        private static final List<String> ADMIN_SUFFIXES = List.of(
                "특별자치도", "특별자치시", "특별시", "광역시", "도", "시", "군", "구");

        private static final String BOUNDARY_BEFORE = "(?<![0-9A-Za-z가-힣])";
        private static final String BOUNDARY_AFTER =
                "(?:$|\\s|[.,!?\"'()·]|(?:시|군|구|도)?(?:에서|으로|로|에|의|은|는|이|가|을|를|도|만|와|과|까지|부터)(?=$|\\s|[.,!?\"'()·]))";

        private record AliasRule(String alias, String code, Pattern pattern) {}

        private final List<AliasRule> rules;

        DynamicExplorationRegionCatalog(List<RegionEntry> entries) {
            // Prefer SIDO over SIGUNGU on identical short alias collisions (e.g. "광주", "제주", "세종"),
            // while preferring longer specific names (e.g. "강원 고성", "경기도 광주시", "전주시 완산구").
            var sortedEntries = entries.stream()
                    .filter(e -> !e.code().isBlank() && !e.name().isBlank())
                    .sorted(Comparator.comparingInt((RegionEntry e) -> "SIDO".equals(e.level()) ? 0 : 1)
                            .thenComparing(RegionEntry::code))
                    .toList();

            Map<String, List<RegionEntry>> aliasOwners = new LinkedHashMap<>();
            for (var entry : sortedEntries) {
                for (var alias : extractAliases(entry)) {
                    aliasOwners.computeIfAbsent(alias, ignored -> new ArrayList<>()).add(entry);
                }
            }

            List<AliasRule> built = new ArrayList<>();
            aliasOwners.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<String, List<RegionEntry>>>comparingInt(
                                    e -> e.getKey().length())
                            .reversed()
                            .thenComparing(Map.Entry::getKey))
                    .forEach(entry -> {
                        String alias = entry.getKey();
                        List<RegionEntry> owners = entry.getValue();
                        RegionEntry selected = selectOwner(owners);
                        if (selected != null) {
                            Pattern pattern = Pattern.compile(
                                    BOUNDARY_BEFORE + Pattern.quote(alias) + BOUNDARY_AFTER);
                            built.add(new AliasRule(alias, selected.code(), pattern));
                        }
                    });
            this.rules = List.copyOf(built);
        }

        @Override
        public Optional<String> resolveRegionCode(String normalizedQuery) {
            if (normalizedQuery == null || normalizedQuery.isBlank()) {
                return Optional.empty();
            }
            for (var rule : rules) {
                if (rule.pattern().matcher(normalizedQuery).find()) {
                    return Optional.of(rule.code());
                }
            }
            return Optional.empty();
        }

        private static RegionEntry selectOwner(List<RegionEntry> owners) {
            if (owners.size() == 1) {
                return owners.getFirst();
            }
            // If a single SIDO owns a short alias alongside a SIGUNGU (e.g. "제주", "세종", "광주"),
            // let the SIDO own the unqualified alias.
            List<RegionEntry> sidos = owners.stream()
                    .filter(e -> "SIDO".equals(e.level()))
                    .toList();
            if (sidos.size() == 1) {
                return sidos.getFirst();
            }
            // Multiple distinct SIGUNGU share identical unqualified names like "중구", "동구", "고성군".
            // Require parent qualification (e.g. "서울 중구", "강원 고성") instead of guessing blindly.
            return null;
        }

        private static Set<String> extractAliases(RegionEntry entry) {
            Set<String> aliases = extractNameAliases(entry.name());
            if (!entry.parentName().isBlank() && !entry.parentName().equals(entry.name())) {
                Set<String> parentAliases = extractNameAliases(entry.parentName());
                Set<String> leafAliases = extractLeafAliases(entry.name());
                for (String parentAlias : parentAliases) {
                    for (String leafAlias : leafAliases) {
                        aliases.add(parentAlias + " " + leafAlias);
                    }
                }
            }
            return aliases;
        }

        private static Set<String> extractNameAliases(String name) {
            Set<String> aliases = new LinkedHashSet<>();
            aliases.add(name);

            List<String> provinceExtras = PROVINCE_ALIASES.get(name);
            if (provinceExtras != null) {
                aliases.addAll(provinceExtras);
            }

            aliases.addAll(extractLeafAliases(name));

            if (name.endsWith("광역시")) {
                String stem = name.substring(0, name.length() - "광역시".length());
                if (stem.length() >= 2) {
                    aliases.add(stem + "시");
                    aliases.add(stem);
                }
            } else if (name.endsWith("특별시")) {
                String stem = name.substring(0, name.length() - "특별시".length());
                if (stem.length() >= 2 && !name.contains("통합")) {
                    aliases.add(stem + "시");
                    aliases.add(stem);
                }
            } else if (name.endsWith("특별자치시")) {
                String stem = name.substring(0, name.length() - "특별자치시".length());
                if (stem.length() >= 2) {
                    aliases.add(stem + "시");
                    aliases.add(stem);
                }
            } else if (name.endsWith("특별자치도")) {
                String stem = name.substring(0, name.length() - "특별자치도".length());
                if (stem.length() >= 2) {
                    aliases.add(stem + "도");
                    aliases.add(stem);
                }
            }
            return aliases;
        }

        private static Set<String> extractLeafAliases(String name) {
            Set<String> aliases = new LinkedHashSet<>();
            if (name.contains(" ")) {
                String[] parts = name.split("\\s+");
                if (parts.length >= 2) {
                    String leaf = parts[parts.length - 1];
                    addStrippedVariants(aliases, leaf);
                }
            } else {
                addStrippedVariants(aliases, name);
            }
            return aliases;
        }

        private static void addStrippedVariants(Set<String> aliases, String raw) {
            if (raw.length() >= 2) {
                aliases.add(raw);
            }
            for (String suffix : ADMIN_SUFFIXES) {
                if (raw.endsWith(suffix)) {
                    String stem = raw.substring(0, raw.length() - suffix.length());
                    if (stem.length() >= 2) {
                        aliases.add(stem);
                    }
                    break;
                }
            }
        }
    }
}
