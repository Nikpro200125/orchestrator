package com.nvp.orchestrator.model;

public record ModelVariable(String name, Class<?> type) {
    public ModelVariable {
        if (name == null || type == null) {
            throw new IllegalArgumentException("Name and type should not be null");
        }
    }

    public boolean isParameter() {
        return !name.startsWith("$result");
    }
}
