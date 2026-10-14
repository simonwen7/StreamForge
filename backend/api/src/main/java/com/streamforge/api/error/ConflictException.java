package com.streamforge.api.error;

public class ConflictException extends RuntimeException {

    public ConflictException(String detail) {
        super(detail);
    }
}
