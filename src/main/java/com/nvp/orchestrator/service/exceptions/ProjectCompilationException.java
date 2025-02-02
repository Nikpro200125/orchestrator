package com.nvp.orchestrator.service.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class ProjectCompilationException extends RuntimeException {
    public ProjectCompilationException(String message) {
        super(message);
    }
}
