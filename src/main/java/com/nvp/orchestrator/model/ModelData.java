package com.nvp.orchestrator.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.nodes.*;
import org.springframework.javapoet.CodeBlock;

import java.lang.reflect.Parameter;
import java.util.*;

import static java.util.regex.Matcher.quoteReplacement;

@Slf4j
@Getter
public class ModelData {
    public final static String FIELD_DELIMITER = "$";
    public final static String RESULT_FIELD = "result";

    private final List<String> allFields = new ArrayList<>();
    private final Class<?> returnClass;
    private final List<Contract> ensures;
    private final Set<ModelVariable> fieldsAffectedByContracts;
    private final CodeBlock modelContracts;
    private final Set<ModelVariable> methodParameters;

    public ModelData(@NotNull Class<?> returnClass, @NotNull List<Contract> ensures, Parameter[] parameters) {
        this.returnClass = returnClass;
        this.ensures = ensures;
        this.methodParameters = gatherParameters(parameters);
        this.fieldsAffectedByContracts = gatherFieldsAffectedByContracts();
        this.modelContracts = generateModelContracts();
    }

    private Set<ModelVariable> gatherParameters(Parameter[] parameters) {
        Set<ModelVariable> fields = new HashSet<>();
        for (Parameter parameter : parameters) {
            fields.add(new ModelVariable(parameter.getName(), parameter.getType()));
        }
        return fields;
    }

    public String restorePathOfParameter(@NotEmpty ModelVariable parameter) {
        String path = parameter.name().replaceFirst(quoteReplacement(FIELD_DELIMITER), "");
        String[] pathArray = getFieldRelativePath(parameter.name());
        if (pathArray.length == 0) {
            return path;
        }

        StringBuilder pathBuilder = new StringBuilder(path.substring(0, path.indexOf(FIELD_DELIMITER)));

        for (String s : pathArray) {
            pathBuilder.append(".get").append(capitalizeFirstLetter(s)).append("()");
        }
        return pathBuilder.toString();
    }

    public void restoreModelContracts(CodeBlock.Builder cbb) {
        for (ModelVariable field : getFieldsToRestore()) {
            String[] path = getFieldRelativePath(field.name());
            if (path.length == 0) {
                cbb.addStatement("answer = $L", getValueSetterByClass(field));
            } else {
                CodeBlock.Builder currentCodeBlockBuilder = CodeBlock.builder();
                currentCodeBlockBuilder.add("answer");
                for (int i = 0; i < path.length - 1; i++) {
                    currentCodeBlockBuilder.add(".get$L()", capitalizeFirstLetter(path[i]));
                }
                currentCodeBlockBuilder.add(".set$L($L)", capitalizeFirstLetter(path[path.length - 1]), getValueSetterByClass(field));
                cbb.addStatement(currentCodeBlockBuilder.build());
            }
        }
        cbb.build();
    }

    private List<ModelVariable> getFieldsToRestore() {
        return fieldsAffectedByContracts.stream().filter(mv -> !mv.isParameter()).toList();
    }

    private static String capitalizeFirstLetter(String str) {
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private CodeBlock getValueSetterByClass(ModelVariable mv) {
        CodeBlock.Builder cbb = CodeBlock.builder();
        if (mv.type() == Integer.class) {
            cbb.add("$L.getValue()", mv.name());
        } else if (mv.type() == Double.class) {
            cbb.add("$T.round(($L.getLB() + $L.getUB()) * 50) / 100.0", Math.class, mv.name(), mv.name());
        } else if (mv.type() == Boolean.class) {
            cbb.add("$L.getValue() == 1", mv.name());
        } else {
            throw new IllegalArgumentException("Unsupported type: " + mv.type());
        }
        return cbb.build();
    }

    private CodeBlock generateModelContracts() {
        CodeBlock.Builder cbb = CodeBlock.builder();
        for (Contract contract : ensures) {
            CodeBlock.Builder contractCodeBlockBuilder = CodeBlock.builder();
            contractCodeBlockBuilder.add(generateModelContracts(contract.getExpression())).add(".post()");
            cbb.addStatement(contractCodeBlockBuilder.build());
        }
        return cbb.build();
    }

    private CodeBlock generateModelContracts(Expression expression) {
        CodeBlock.Builder cbb = CodeBlock.builder();

        if (!(expression instanceof BinaryOpExpression binaryOpExpression)) {
            throw new IllegalArgumentException("Unsupported expression type: " + expression.getClass().getName());
        }

        CodeBlock left = expressionToCodeBlock(binaryOpExpression.getLeft());

        CodeBlock right = expressionToCodeBlock(binaryOpExpression.getRight());

        cbb.add("$L.$L($L)", left, convertOpToMethod(binaryOpExpression.getOp()), right);

        return cbb.build();
    }

    private CodeBlock expressionToCodeBlock(Expression expression) {
        return switch (expression) {
            case VariableAccess variableAccess -> CodeBlock.of("$L", convertVariableAccessToStringName(variableAccess));
            case BinaryOpExpression binaryOpExpression -> generateModelContracts(binaryOpExpression);
            case IntegerLiteral integerLiteral -> CodeBlock.of("$L", integerLiteral.getValue());
            case FloatLiteral floatLiteral -> CodeBlock.of("$L", floatLiteral.getValue());
            case BoolLiteral boolLiteral -> CodeBlock.of("$L", boolLiteral.getValue() ? 1 : 0);
            default -> throw new IllegalArgumentException("Unsupported expression type: " + expression.getClass().getName());
        };
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
            case MUL -> "mul";
            case ADD -> "add";
            case SUB -> "sub";
            case DIV -> "div";
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

        return getFieldNameIfExist(expression);
    }

    private Set<ModelVariable> getFieldNameIfExist(Expression expression) {
        Set<ModelVariable> fields = new HashSet<>();
        switch (expression) {
            case BinaryOpExpression binaryOpExpression -> {
                fields.addAll(getFieldNameIfExist(binaryOpExpression.getLeft()));
                fields.addAll(getFieldNameIfExist(binaryOpExpression.getRight()));
            }
            case UnaryOpExpression unaryOpExpression -> fields.addAll(getFieldNameIfExist(unaryOpExpression.getValue()));
            case VariableAccess variableAccess -> {
                ModelVariable fieldName = getFieldVariable(variableAccess);
                fields.add(fieldName);
            }
            case null -> throw new IllegalArgumentException("Expression is null");
            default -> log.debug("Skipping expression: {}", expression);
        }

        return fields;
    }

    private String convertVariableAccessToStringName(VariableAccess variableAccess) {
        String dumpedToString = variableAccess.dumpToString();
        return FIELD_DELIMITER + dumpedToString.replaceAll("\\.", quoteReplacement(FIELD_DELIMITER));
    }

    private ModelVariable getFieldVariable(VariableAccess variableAccess) {
        String dumpedToString = variableAccess.dumpToString();
        String replacedName = convertVariableAccessToStringName(variableAccess);
        if (dumpedToString.contains(RESULT_FIELD)) {
            Class<?> fieldClass = getClassByFieldNameOfResult(replacedName);
            return new ModelVariable(replacedName, fieldClass);
        } else {
            ModelVariable modelVariable = methodParameters.stream().filter(mp -> mp.name().equals(variableAccess.getFieldName())).findFirst().orElseThrow();
            Class<?> fieldClass = getClassByFieldName(replacedName, modelVariable.type());
            return new ModelVariable(replacedName, fieldClass);
        }
    }

    private Class<?> getClassByFieldName(String fieldName, Class<?> sourceClass) {
        String[] path = getFieldRelativePath(fieldName);
        Class<?> currentClass = sourceClass;
        for (String field : path) {
            currentClass = getFieldClass(currentClass, field);
        }
        return currentClass;
    }

    private Class<?> getClassByFieldNameOfResult(String fieldName) {
        return getClassByFieldName(fieldName, returnClass);
    }

    /**
     * path from the root of the object <br/>
     * e.g. $a$b$c -> [b, c] <br/>
     * e.g. $result$b$c -> [b, c] <br/>
     * e.g. $x -> [] <br/>
     * e.g. x -> [x] <br/>
     * e.g. x$y&b -> [y, b] <br/>
     */
    private static String @NotNull [] getFieldRelativePath(String fieldName) {
        if (fieldName.matches("^" + quoteReplacement(FIELD_DELIMITER) + "[a-z]+$")) {
            return new String[0];
        }
        String fieldPath = fieldName.replaceFirst("^(?:" + quoteReplacement(FIELD_DELIMITER) + ")?" + "[a-z]+" + quoteReplacement(FIELD_DELIMITER), "");
        return fieldPath.isBlank() ? new String[0] : fieldPath.split(quoteReplacement(FIELD_DELIMITER));
    }

    private Class<?> getFieldClass(Class<?> currentClass, String field) {
        try {
            return currentClass.getDeclaredField(field).getType();
        } catch (NoSuchFieldException e) {
            throw new IllegalArgumentException("Field " + field + " not found in class " + currentClass.getName());
        }
    }

}