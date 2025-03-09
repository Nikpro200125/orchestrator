package org.openapitools.service;

import org.openapitools.model.EndpointOverrideConfig;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ControlPanelService {
    private final Map<String, EndpointOverrideConfig> configMap = new ConcurrentHashMap<>();

    public EndpointOverrideConfig getConfig(String endpoint) {
        return configMap.get(endpoint);
    }

    public Map<String, EndpointOverrideConfig> getAllConfigs() {
        return configMap;
    }

    public void setConfig(String endpoint, EndpointOverrideConfig config) {
        configMap.put(endpoint, config);
    }

    public void resetConfig(String endpoint) {
        configMap.remove(endpoint);
    }

    public void resetAll() {
        configMap.clear();
    }
}