package com.bethanysbakery.api.common.error;

import java.util.List;

/** Publish-time validation failure returned with field-level problem details. */
public class PublishValidationException extends RuntimeException {

    private final List<Problem.FieldError> errors;

    public PublishValidationException(List<Problem.FieldError> errors) {
        super("Menu publish validation failed");
        this.errors = List.copyOf(errors);
    }

    public List<Problem.FieldError> errors() {
        return errors;
    }
}
