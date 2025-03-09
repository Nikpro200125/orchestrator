package org.openapitools.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RequestCounterService {
    private final Map<String, Long> endpointCounts = new ConcurrentHashMap<>();

    public void increment(String endpoint) {
        endpointCounts.merge(endpoint, 1L, Long::sum);
    }

    public Map<String, Long> getCounts() {
        return endpointCounts;
    }

    public void reset() {
        endpointCounts.clear();
    }
}