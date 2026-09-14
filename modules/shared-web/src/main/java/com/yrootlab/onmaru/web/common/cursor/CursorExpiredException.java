package com.yrootlab.onmaru.web.common.cursor;

public class CursorExpiredException extends RuntimeException {

    public CursorExpiredException() {
        super("cursor is expired");
    }
}
