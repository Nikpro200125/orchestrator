package com.nvp.orchestrator.model;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import org.jetbrains.research.libsl.nodes.*;
import org.springframework.javapoet.CodeBlock;

import java.util.*;

import static java.util.regex.Matcher.quoteReplacement;

@Getter
public class ModelData {
    private final static String FIELD_DELIMITER = "$";
    private final static String RESULT_FIELD = "result";

    private final List<String> allFields = new ArrayList<>();
    private final Class<?> returnClass;
    private final List<Contract> ensures;
    private final Set<ModelVariable> fieldsAffectedByContracts;
    private final CodeBlock modelContracts;

    public ModelData(@NotNull Class<?> returnClass, @NotNull List<Contract> ensures) {
        this.returnClass = returnClass;
        this.ensures = ensures;
        this.fieldsAffectedByContracts = gatherFieldsAffectedByContracts();
        this.modelContracts = generateModelContracts();
    }

    public void restoreModelContracts(CodeBlock.Builder codeBlockBuilder) {
        for (ModelVariable field : fieldsAffectedByContracts) {
            String[] path = getFieldPath(field.name());
            if (path.length == 0) {
                codeBlockBuilder.addStatement("answer = $L", getValueSetterByClass(field));
            } else {
                CodeBlock.Builder currentCodeBlockBuilder = CodeBlock.builder();
                currentCodeBlockBuilder.add("answer");
                for (int i = 0; i < path.length - 1; i++) {
                    currentCodeBlockBuilder.add(".get$L()", path[i]);
                }
                currentCodeBlockBuilder.add(".set$L($L)", path[path.length - 1], getValueSetterByClass(field));
                codeBlockBuilder.addStatement(currentCodeBlockBuilder.build());
            }
        }
        codeBlockBuilder.build();
    }

    private CodeBlock getValueSetterByClass(ModelVariable mv) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();
        if (mv.type() == Integer.class) {
            codeBlockBuilder.add("$L.getValue()", mv.name());
        } else if (mv.type() == Double.class) {
            codeBlockBuilder.add("($L.getLB() + $L.getUB()) / 2.0", mv.name(), mv.name());
        } else {
            throw new IllegalArgumentException("Unsupported type: " + mv.type());
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock generateModelContracts() {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();
        for (Contract contract : ensures) {
            CodeBlock.Builder contractCodeBlockBuilder = CodeBlock.builder();
            contractCodeBlockBuilder.add(generateModelContracts(contract.getExpression())).add(".post()");
            codeBlockBuilder.addStatement(contractCodeBlockBuilder.build());
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock generateModelContracts(Expression expression) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();

        if (expression instanceof BinaryOpExpression binaryOpExpression) {
            if (binaryOpExpression.getLeft() instanceof VariableAccess leftVariableAccess
                    && binaryOpExpression.getRight() instanceof VariableAccess rightVariableAccess)
            {

                ModelVariable leftField = getFieldVariable(leftVariableAccess);
                ModelVariable rightField = getFieldVariable(rightVariableAccess);

                codeBlockBuilder.add("$L.$L($L)", leftField.name(), convertOpToMethod(binaryOpExpression.getOp()), rightField.name());

            } else if (binaryOpExpression.getLeft() instanceof VariableAccess
                    || binaryOpExpression.getRight() instanceof VariableAccess) {
                throw new IllegalArgumentException("Both left and right expressions should be VariableAccess");
            } else {
                CodeBlock left = generateModelContracts(binaryOpExpression.getLeft());
                CodeBlock right = generateModelContracts(binaryOpExpression.getRight());
                codeBlockBuilder.add("($L).$L($L)", left, convertOpToMethod(binaryOpExpression.getOp()), right);
            }
        } else {
            throw new IllegalArgumentException("Unsupported expression type: " + expression.getClass().getName());
        }
        return codeBlockBuilder.build();
    }

    private static String convertOpToMethod(ArithmeticBinaryOps op) {
        return switch (op) {
            case LOG_AND -> "and";
            case LOG_OR -> "or";
            case GT -> "gt";
            case LT -> "lt";
            case GT_EQ -> "ge";
            case LT_EQ -> "le";
            case EQ -> "eq";
            case NOT_EQ -> "ne";
            default -> throw new IllegalArgumentException("Unsupported operation: " + op);
        };
    }

    private Set<ModelVariable> gatherFieldsAffectedByContracts() {
        Set<ModelVariable> fields = new HashSet<>();
        for (Contract contract : ensures) {
            fields.addAll(gatherFieldsAffectedByContract(contract));
        }
        return fields;
    }

    private Set<ModelVariable> gatherFieldsAffectedByContract(Contract contract) {
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
                if (fieldName.type() != null) {
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
            return new ModelVariable(dumpedToString, null);
        }
    }

    private Class<?> getClassByFieldName(String fieldName) {
        String[] path = getFieldPath(fieldName);
        Class<?> currentClass = returnClass;
        for (String field : path) {
            currentClass = getFieldClass(currentClass, field);
        }
        return currentClass;
    }

    private static String @NotNull [] getFieldPath(String fieldName) {
        String fieldPath = fieldName.replaceFirst("^" + RESULT_FIELD + "(?:" + quoteReplacement(FIELD_DELIMITER) + ")?", "");
        return fieldPath.isBlank() ? new String[0] : fieldPath.split(quoteReplacement(FIELD_DELIMITER));
    }

    private Class<?> getFieldClass(Class<?> currentClass, String field) {
        try {
            return currentClass.getDeclaredField(field).getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("Field " + field + " not found in class " + currentClass.getName());
        }
    }

    public boolean isNeedToCreateClass() {
        return this.fieldsAffectedByContracts.stream().anyMatch(mv -> mv.name().equals(RESULT_FIELD));
    }

}