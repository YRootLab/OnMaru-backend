package com.yrootlab.onmaru.stamp;

public record StampRegionRule(String stampCode, String regionCode) {

    public boolean matches(String actualRegionCode) {
        return actualRegionCode != null
                && (actualRegionCode.equals(regionCode) || actualRegionCode.startsWith(regionCode + "-"));
    }
}
