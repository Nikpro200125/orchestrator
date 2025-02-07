package com.nvp.orchestrator.model;

public record ModelVariable(String name, Class<?> type) {
    public ModelVariable {
        if (name == null) {
            throw new IllegalArgumentException("Name and type should not be null");
        }
    }
}
