package com.streamforge.api.error;

import java.util.List;

public class InvalidRequestException extends RuntimeException {

    private final List<FieldError> errors;

    public InvalidRequestException(List<FieldError> errors) {
        super("Request validation failed.");
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }

    public record FieldError(String field, String message) {
    }
}
