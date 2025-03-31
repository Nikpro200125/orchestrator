package com.nvp.orchestrator.model;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.research.libsl.nodes.*;
import org.jetbrains.research.libsl.type.*;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.CodeBlock;
import org.springframework.javapoet.MethodSpec;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class BodyFunction {
    private final Function function;
    private final Type returnType;
    private final URLClassLoader urlClassLoader;
    private final Library library;

    public BodyFunction(Function function, Type returnType, URLClassLoader urlClassLoader, Library library) {
        this.function = function;
        this.returnType = returnType;
        this.urlClassLoader = urlClassLoader;
        this.library = library;
    }

    public void generateBodyFunction(MethodSpec.Builder methodBuilder) {
        generateReturnObject(methodBuilder);
        List<Statement> statements = function.getStatements();
        CodeBlock.Builder cbb = CodeBlock.builder();

        for (Statement statement : statements) {
            generateStatement(statement, cbb);
        }

        methodBuilder.addCode(cbb.build());

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

    private void generateStatement(Statement statement, CodeBlock.Builder methodBuilder) {
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
        return switch (expression) {
            case VariableAccess variableAccess -> {
                if (isTopLevel) {
                    if (variableAccess.getChildAccess() != null) {
                        yield variableAccess.getFieldName() + resolveExpression(variableAccess.getChildAccess(), isRightValue, false);
                    }

                    yield variableAccess.getFieldName();
                } else {
                    if (variableAccess.getChildAccess() != null) {
                        yield ".get" + ModelData.capitalizeFirstLetter(variableAccess.getFieldName()) + "()" + resolveExpression(variableAccess.getChildAccess(), isRightValue, false);
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
                    String procName = procExpression.getProcedureCall().getName();
                    List<Expression> arguments = procExpression.getProcedureCall().getArguments();
                    Function libFunction = library.getAutomata().stream().map(Automaton::getProcDeclarations).flatMap(List::stream)
                            .filter(f -> f.getName().equals(procName))
                            .findFirst()
                            .orElseThrow(() -> new GenerationImplementationException("Function not found: " + procName));

                    // Map arguments to parameters
                    if (arguments.size() != libFunction.getArgs().size()) {
                        throw new GenerationImplementationException("Argument count mismatch for function: " + procName);
                    }


                    // Build argument mapping from procedure parameters to provided arguments
                    Map<String, String> argMapping = new HashMap<>();
                    List<FunctionArgument> procArgs = libFunction.getArgs();
                    for (int i = 0; i < procArgs.size(); i++) {
                        String argName = procArgs.get(i).getName();
                        String resolvedArgValue = resolveExpression(arguments.get(i), true);
                        argMapping.put(argName, resolvedArgValue);
                    }

                    // Collect all variable declarations in the procedure to prefixify them
                    List<String> procedureLocalVars = libFunction.getStatements().stream()
                            .filter(s -> s instanceof VariableDeclaration)
                            .map(s -> ((VariableDeclaration) s).getVariable().getName())
                            .toList();

                    CodeBlock.Builder cbb = CodeBlock.builder();
                    // Modify the code generation to use prefixed variable names
                    libFunction.getStatements().forEach(
                            statement -> generateStatement(statement, cbb)
                    );


                    // Generate a random prefix for all procedure local variables

                    String procPrefix = "__proc_" + Math.abs(procName.hashCode()) + "_";
                    procedureLocalVars.forEach(
                            plv -> argMapping.putIfAbsent(plv, procPrefix + plv)
                    );

                    // Replace arguments in the code block
                    String inlinedCode = cbb.build().toString();
                    // Replace all argument references in the inlined code
                    // First, create unique placeholders for each variable to avoid interference
                    Map<String, String> placeholders = new HashMap<>();
                    int uniqueId = 0;
                    for (String key : argMapping.keySet()) {
                        String placeholder = "__TEMP_PLACEHOLDER_" + (uniqueId++) + "__";
                        placeholders.put(key, placeholder);

                        // Replace original variable with placeholder
                        String pattern = "\\b" + Pattern.quote(key) + "\\b";
                        inlinedCode = inlinedCode.replaceAll(pattern, Matcher.quoteReplacement(placeholder));
                    }

                    // Replace placeholders with prefixed vars or argument values
                    for (Map.Entry<String, String> entry : placeholders.entrySet()) {
                        String key = entry.getKey();
                        String placeholder = entry.getValue();

                        if (argMapping.containsKey(key)) {
                            inlinedCode = inlinedCode.replace(placeholder, argMapping.get(key));
                        } else {
                            inlinedCode = inlinedCode.replace(placeholder, procPrefix + key);
                        }
                    }

                    log.debug("Inlined function: {}", procName);
                    boolean hasResult = libFunction.getReturnType() != null;
                    if (hasResult) {
                        inlinedCode = inlinedCode.replace("result =", "return");
                        yield CodeBlock.builder().add("(($T<$T>) () -> { " + inlinedCode + " }).get()", Supplier.class, resolveType(libFunction.getReturnType().resolve())).build().toString();
                    } else {
                        // Use Consumer pattern for code that doesn't return a result
                        yield CodeBlock.builder().add("(($T<$T>) (x) -> { " + inlinedCode + " }).accept(null)", Consumer.class, Object.class).build().toString();
                    }
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
