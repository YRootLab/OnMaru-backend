package com.yrootlab.onmaru.web.health;

record PublicHealthResponse(String status) {

    static PublicHealthResponse up() {
        return new PublicHealthResponse("UP");
    }
}
