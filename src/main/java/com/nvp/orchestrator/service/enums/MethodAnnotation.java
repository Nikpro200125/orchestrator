package com.nvp.orchestrator.service.enums;

public enum MethodAnnotation {
    GET, POST, PUT, DELETE;

    public static boolean isMethodAnnotation(String value) {
        for (MethodAnnotation methodAnnotation : MethodAnnotation.values()) {
            if (methodAnnotation.name().equals(value)) {
                return true;
            }
        }
        return false;
    }
}