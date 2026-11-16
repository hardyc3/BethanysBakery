package com.bethanysbakery.api.common.error;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
    String type,
    String title,
    int status,
    String detail,
    String instance,
    String code,
    String requestId,
    List<FieldError> errors
) {
    public record FieldError(String field, String message) {}
}
