package com.yrootlab.onmaru.catalog.publicid;

public final class CatalogPublicPlaceIdConflictException extends RuntimeException {

    public CatalogPublicPlaceIdConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public CatalogPublicPlaceIdConflictException(String message) {
        super(message);
    }
}
