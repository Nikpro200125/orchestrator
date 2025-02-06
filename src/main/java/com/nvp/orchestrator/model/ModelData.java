package com.nvp.orchestrator.model;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.jetbrains.research.libsl.nodes.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.util.regex.Matcher.quoteReplacement;

@Getter
public class ModelData {
    private final static String FIELD_DELIMITER = "$";
    private final static String RESULT_FIELD = "result";

    private final List<String> allFields = new ArrayList<>();
    private final Class<?> returnClass;
    private final List<Contract> ensures;
    private final Set<ModelVariable> fieldsAffectedByContracts;

    public ModelData(@NotNull Class<?> returnClass, @NotNull List<Contract> ensures) {
        this.returnClass = returnClass;
        this.ensures = ensures;
        this.fieldsAffectedByContracts = getFieldsAffectedByContracts();
    }

    private Set<ModelVariable> getFieldsAffectedByContracts() {
        Set<ModelVariable> fields = new HashSet<>();
        for (Contract contract : ensures) {
            fields.addAll(getFieldsAffectedByContract(contract));
        }
        return fields;
    }

    private Set<ModelVariable> getFieldsAffectedByContract(Contract contract) {
        Expression expression = contract.getExpression();

        return getFiledNameIfExist(expression);
    }

    private Set<ModelVariable> getFiledNameIfExist(Expression expression) {
        Set<ModelVariable> fields = new HashSet<>();
        switch (expression) {
            case BinaryOpExpression binaryOpExpression -> {
                fields.addAll(getFiledNameIfExist(binaryOpExpression.getLeft()));
                fields.addAll(getFiledNameIfExist(binaryOpExpression.getRight()));
            }
            case UnaryOpExpression unaryOpExpression ->
                    fields.addAll(getFiledNameIfExist(unaryOpExpression.getValue()));
            case VariableAccess variableAccess -> {
                ModelVariable fieldName = getFieldVariable(variableAccess);
                if (fieldName != null) {
                    fields.add(fieldName);
                }
            }
            case null -> throw new IllegalArgumentException("Expression is null");
            default -> throw new IllegalArgumentException("Unsupported expression type: " + expression.getClass().getName());
        }

        return fields;
    }

    private ModelVariable getFieldVariable(VariableAccess variableAccess) {
        String dumpedToString = variableAccess.dumpToString();
        if (dumpedToString.contains(RESULT_FIELD)) {
            String name = dumpedToString.replaceAll("\\.", quoteReplacement(FIELD_DELIMITER));
            Class<?> fieldClass = getClassByFieldName(name);
            return new ModelVariable(name, fieldClass);
        } else {
            return null;
        }
    }

    private Class<?> getClassByFieldName(String fieldName) {
        String filedPath = fieldName.replaceFirst("^" + RESULT_FIELD + "(?:\\" + FIELD_DELIMITER + ")?", "");
        String[] path = filedPath.isBlank() ? new String[0] : filedPath.split(FIELD_DELIMITER);
        Class<?> currentClass = returnClass;
        for (String field : path) {
            currentClass = getFieldClass(currentClass, field);
        }
        return currentClass;
    }

    private Class<?> getFieldClass(Class<?> currentClass, String field) {
        try {
            return currentClass.getDeclaredField(field).getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("Field " + field + " not found in class " + currentClass.getName());
        }
    }

}

record ModelVariable(String name, Class<?> type) {
    public ModelVariable {
        if (name == null || type == null) {
            throw new IllegalArgumentException("Name and type should not be null");
        }
    }
}
