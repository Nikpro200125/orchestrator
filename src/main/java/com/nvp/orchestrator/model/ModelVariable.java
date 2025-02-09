package com.nvp.orchestrator.model;

import static java.util.regex.Matcher.quoteReplacement;

public record ModelVariable(String name, Class<?> type) {
    public ModelVariable {
        if (name == null || type == null) {
            throw new IllegalArgumentException("Name and type should not be null");
        }
    }

    public boolean isParameter() {
        return !name.matches("^(?:" + quoteReplacement(ModelData.FIELD_DELIMITER) + ")?" + ModelData.RESULT_FIELD + ".*");
    }
}
