package org.openapitools.model;

import lombok.Data;

@Data
public class EndpointOverrideConfig {
    private Long delayMs;
    private Integer httpCodeOverride;
}