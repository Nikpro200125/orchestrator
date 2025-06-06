package com.nvp.orchestrator.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
public class DockerException extends RuntimeException {
    public DockerException(String message) {
        super(message);
    }
}
