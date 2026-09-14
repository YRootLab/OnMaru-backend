package com.yrootlab.onmaru.web.common.cursor;

public class CursorInvalidException extends RuntimeException {

    public CursorInvalidException() {
        super("cursor is invalid");
    }

    public CursorInvalidException(Throwable cause) {
        super("cursor is invalid", cause);
    }
}
