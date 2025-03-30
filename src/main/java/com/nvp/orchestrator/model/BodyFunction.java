package com.nvp.orchestrator.model;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.nodes.*;
import org.jetbrains.research.libsl.type.*;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.MethodSpec;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Objects;

@Slf4j
public class BodyFunction {
    private final Method method;
    private final Function function;
    private final Type returnType;
    private final URLClassLoader urlClassLoader;

    public BodyFunction(Method method, Function function, Type returnType, URLClassLoader urlClassLoader) {
        this.method = method;
        this.function = function;
        this.returnType = returnType;
        this.urlClassLoader = urlClassLoader;
    }

    public void generateBodyFunction(MethodSpec.Builder methodBuilder) {
        generateReturnObject(methodBuilder);
        List<Statement> statements = function.getStatements();

        for (Statement statement : statements) {
            generateStatement(statement, methodBuilder);
        }

        generateReturnStatement(methodBuilder);
    }

    private void generateReturnObject(MethodSpec.Builder methodBuilder) {
        if (returnType == null) {
            log.error("Return type is null");
            throw new GenerationImplementationException("Return type is null");
        }

        if (returnType instanceof ParameterizedType returnClass) {
            if (returnClass.getRawType() == ResponseEntity.class) {
                String returnObjectName = "result";
                Class<?> returnObjectClass = (Class<?>) returnClass.getActualTypeArguments()[0];
                methodBuilder.addStatement("$T $L = null", returnObjectClass, returnObjectName);
                return;
            }
        }

        throw new GenerationImplementationException("Return type is " + returnType);
    }

    private void generateReturnStatement(MethodSpec.Builder methodBuilder) {
        methodBuilder.addStatement("return $T.ok(result)", ResponseEntity.class);
    }

    private void generateStatement(Statement statement, MethodSpec.Builder methodBuilder) {
        switch (statement) {
            case VariableDeclaration variableDeclaration -> {
                log.debug("Variable declaration: {}", variableDeclaration);
                VariableWithInitialValue variable = variableDeclaration.getVariable();
                ModelVariable modelVariable = new ModelVariable(variable.getName(), resolveType(variable.getTypeReference().resolve()));
                methodBuilder.addStatement("$T $L = $L", modelVariable.type(), modelVariable.name(), variable.getInitialValue() == null ? "null" : resolveExpression(variable.getInitialValue(), true));
            }
            case Assignment assignment -> {
                log.debug("Assignment: {}", assignment);
                String variableName = resolveExpression(assignment.getLeft(), false);

                String value = resolveExpression(assignment.getValue(), true);

                if (assignment.getLeft() instanceof VariableAccess variableAccess) {
                    if (variableAccess.getChildAccess() != null) {
                        // update the value of the child access using setters
                        methodBuilder.addStatement("$L$L)", variableName, value);
                        return;
                    }
                }

                methodBuilder.addStatement("$L = $L", variableName, value);
            }
            default -> {
                log.error("Unknown statement type: {}", statement);
                throw new GenerationImplementationException("Unknown statement type: " + statement);
            }
        }
    }

    private String resolveExpression(Expression expression, boolean isRightValue) {
        return resolveExpression(expression, isRightValue, true);
    }

    private String resolveExpression(Expression expression, boolean isRightValue, boolean isTopLevel) {
        return switch(expression) {
            case VariableAccess variableAccess -> {
                if (isTopLevel) {
                    if (variableAccess.getChildAccess() != null) {
                        yield variableAccess.getFieldName() + resolveExpression(variableAccess.getChildAccess(), isRightValue, false);
                    }

                    yield variableAccess.getFieldName();
                } else {
                    if (variableAccess.getChildAccess() != null) {
                        yield ".get" + ModelData.capitalizeFirstLetter(variableAccess.getFieldName()) + "()" + resolveExpression(variableAccess.getChildAccess(), isRightValue,false);
                    }

                    yield (isRightValue
                            ? ".get" + ModelData.capitalizeFirstLetter(variableAccess.getFieldName()) + "()"
                            : ".set" + ModelData.capitalizeFirstLetter(variableAccess.getFieldName()) + "(");
                }
            }
            case ArrayAccess arrayAccess -> {
                String index = Objects.requireNonNull(arrayAccess.getIndex().getValue()).toString();
                yield (isRightValue ? ".get(" + index + ")" : ".set(" + index);
            }
            case BinaryOpExpression binaryOpExpression -> {
                String left = resolveExpression(binaryOpExpression.getLeft(), isRightValue);
                String right = resolveExpression(binaryOpExpression.getRight(), isRightValue);
                String operator = binaryOpExpression.getOp().getString();
                yield left + " " + operator + " " + right;
            }
            case IntegerLiteral integerLiteral -> {
                yield Objects.requireNonNull(integerLiteral.getValue()).toString();
            }
            case StringLiteral stringLiteral -> {
                String value = Objects.requireNonNull(stringLiteral.getValue());
                yield "\"" + value + "\"";
            }
            case FloatLiteral floatLiteral -> {
                yield Objects.requireNonNull(floatLiteral.getValue()).toString();
            }
            case ProcExpression procExpression -> {
                // is constructor
                try {
                    Class<?> clazz = resolveClassByOldType(procExpression.getProcedureCall().getName());
                    yield "new " + clazz.getSimpleName() + "()";
                } catch (ClassNotFoundException e) {
                    throw new GenerationImplementationException("Class not found: " + procExpression.getProcedureCall().getName());
                }
            }
            default -> {
                log.error("Unknown expression type: {}", expression);
                throw new GenerationImplementationException("Unknown expression type: " + expression);
            }
        };
    }

    private Class<?> resolveType(org.jetbrains.research.libsl.type.Type type) {
        return switch (type) {
            case Int8Type ignored -> Integer.class;
            case Int16Type ignored -> Integer.class;
            case Int32Type ignored -> Integer.class;
            case Int64Type ignored -> Long.class;
            case Float32Type ignored -> Float.class;
            case Float64Type ignored -> Double.class;
            case BoolType ignored -> Boolean.class;
            case StringType ignored -> String.class;
            case TypeAlias typeAlias -> resolveType(typeAlias.getOriginalType().resolve());
            case StructuredType structuredType -> {
                String className = structuredType.getName();
                try {
                    yield urlClassLoader.loadClass("org.openapitools.model." + className);
                } catch (ClassNotFoundException e) {
                    log.error("Class not found: {}", className, e);
                    throw new GenerationImplementationException("Class not found: " + className);
                }
            }
            default -> {
                log.info("Not specified type: {}", type);
               throw new GenerationImplementationException("Not specified type: " + type);
            }
        };
    }

    private Class<?> resolveClassByOldType(String className) throws ClassNotFoundException {
        return urlClassLoader.loadClass("org.openapitools.model." + className);
    }

}
