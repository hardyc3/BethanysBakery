package com.bethanysbakery.api.common.error;

import java.util.List;

/** Domain-level validation failure carrying field-level problem details. */
public class RequestValidationException extends RuntimeException {

    private final List<Problem.FieldError> errors;

    public RequestValidationException(String field, String message) {
        this(List.of(new Problem.FieldError(field, message)));
    }

    public RequestValidationException(List<Problem.FieldError> errors) {
        super("Request validation failed");
        this.errors = List.copyOf(errors);
    }

    public List<Problem.FieldError> errors() {
        return errors;
    }
}
