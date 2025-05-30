package com.nvp.orchestrator.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class GenerationServiceException extends RuntimeException {
    public GenerationServiceException(String message) {
        super(message);
    }

    public GenerationServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
