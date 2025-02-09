package com.nvp.orchestrator.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ParameterType {
    PATH("PathParameter"),
    REQUEST_BODE("RequestBody"),
    // Not supported yet
    QUERY("QueryParameter");

    private String value;
}

