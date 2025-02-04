package com.nvp.orchestrator.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class OpenApiGenerationException extends RuntimeException {
    public OpenApiGenerationException(String message) {
        super(message);
    }
}
