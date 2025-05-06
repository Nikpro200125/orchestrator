package com.nvp.orchestrator.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ParameterType {
    PATH("PathParameter"),
    REQUEST_BODE("RequestBody"),
    QUERY("QueryParameter");

    private final String value;
}

