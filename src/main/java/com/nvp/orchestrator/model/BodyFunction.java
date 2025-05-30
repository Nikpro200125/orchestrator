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
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
                String value = resolveExpression(variable.getInitialValue(), true);
                if (variable.getInitialValue() instanceof ProcExpression procExpression) {
                    Matcher m = Pattern.compile("__proc_[0-9]+_").matcher(value);
                    String returnVariable = m.find()
                            ? m.group()
                            : null;
                    if (returnVariable != null) {
//                        log.error("Return variable not found in variable declaration: {}", variableDeclaration);
//                        throw new GenerationImplementationException("Return variable not found in variable declaration: " + variableDeclaration);
                        methodBuilder.add(value);
                        value = returnVariable;
                    }
                }
                methodBuilder.addStatement("$T $L = $L", modelVariable.type(), modelVariable.name(), variable.getInitialValue() == null ? "null" : value);
            }
            case Assignment assignment -> {
                log.debug("Assignment: {}", assignment);
                String variableName = resolveExpression(assignment.getLeft(), false);

                String value = resolveExpression(assignment.getValue(), true);

                if (assignment.getValue() instanceof ProcExpression procExpression) {
                    value = resolveExpression(assignment.getValue(), true);
                    Matcher m = Pattern.compile("__proc_[0-9]+_").matcher(value);
                    String returnVariable = m.find()
                            ? m.group()
                            : null;
                    if (returnVariable != null) {
//                            log.error("Return variable not found in assignment: {}", assignment);
//                            throw new GenerationImplementationException("Return variable not found in assignment: " + assignment);
                        methodBuilder.add(value);
                        value = returnVariable;
                    }
                }

                if (assignment.getLeft() instanceof VariableAccess variableAccess) {
                    if (variableAccess.getChildAccess() != null) {
                        // update the value of the child access using setters
                        methodBuilder.addStatement("$L$L)", variableName, value);
                        return;
                    }
                }

                methodBuilder.addStatement("$L = $L", variableName, value);
            }
            case ExpressionStatement expressionStatement -> {
                log.debug("Expression statement: {}", expressionStatement);
                String expression = resolveExpression(expressionStatement.getExpression(), true);
                methodBuilder.add(expression);
            }
            case IfStatement ifStatement -> {
                log.debug("If statement: {}", ifStatement);
                String condition = resolveExpression(ifStatement.getValue(), true);
                methodBuilder.beginControlFlow("if ($L)", condition);
                CodeBlock.Builder cbb = CodeBlock.builder();
                for (Statement state : ifStatement.getIfStatements()) {
                    generateStatement(state, cbb);
                }
                methodBuilder.add(cbb.build());
                if (ifStatement.getElseStatements() != null && !ifStatement.getElseStatements().getStatements().isEmpty()) {
                    methodBuilder.nextControlFlow("else");
                    CodeBlock.Builder elseCbb = CodeBlock.builder();
                    for (Statement state : ifStatement.getElseStatements().getStatements()) {
                        generateStatement(state, elseCbb);
                    }
                    methodBuilder.add(elseCbb.build());
                }
                methodBuilder.endControlFlow();
            }
            default -> {
                log.error("Unknown statement type: {}", statement);
                throw new GenerationImplementationException("Unknown statement type: " + statement);
            }
        }
    }

    public String resolveExpression(Expression expression, boolean isRightValue) {
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
                yield (isRightValue ? ".get(" + index + ")" : ".set(" + index + ", ");
            }
            case UnaryOpExpression unaryOpExpression -> {
                String value = resolveExpression(unaryOpExpression.getValue(), isRightValue);
                if (unaryOpExpression.getOp() == ArithmeticUnaryOp.INVERSION) {
                    yield "!" + value;
                } else {
                    log.error("Unknown unary operator: {}", unaryOpExpression.getOp());
                    throw new GenerationImplementationException("Unknown unary operator: " + unaryOpExpression.getOp());
                }
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
                if (List.of("nextInt", "nextDouble").contains(procExpression.getProcedureCall().getName())) {
                    CodeBlock.Builder cbb = CodeBlock.builder();
                    cbb.add("new $T()."
                            + procExpression.getProcedureCall().getName()
                            + "("
                            + procExpression.getProcedureCall().getArguments().stream()
                            .map(arg -> ((Atomic) arg).getValue().toString())
                            .collect(Collectors.joining(", "))
                            + ")", Random.class);
                    yield cbb.build().toString();
                }
                if ("equals".equals(procExpression.getProcedureCall().getName())) {
                    String left = resolveExpression(procExpression.getProcedureCall().getArguments().get(0), isRightValue);
                    String right = resolveExpression(procExpression.getProcedureCall().getArguments().get(1), isRightValue);
                    yield CodeBlock.builder().add("$T.equals(" + left + ", " + right + ")", Objects.class).build().toString();
                }
                // is constructor
                try {
                    if (!isRightValue) {
                        throw new GenerationImplementationException("Constructor call in assignment without left value" + procExpression);
                    }
                    Class<?> clazz = resolveClassByOldType(procExpression.getProcedureCall().getName());
                    yield CodeBlock.builder().add("new $T()", clazz).build().toString();
                } catch (ClassNotFoundException e) {
                    String procName = procExpression.getProcedureCall().getName();
                    if (List.of("add").contains(procName)) {
                        // Handle special case for add
                        yield ".add(" + procExpression.getProcedureCall().getArguments().stream()
                                .map(Node::toString)
                                .collect(Collectors.joining(", ")) + ");";
                    }
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

                    String procPrefix = "__proc_" + System.currentTimeMillis() + "_";
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
                        inlinedCode = inlinedCode.replace("result =", CodeBlock.builder().add("$T $L = ", resolveType(libFunction.getReturnType().resolve()), procPrefix).build().toString());
                    }
                    yield inlinedCode;
                }
            }
            case ActionExpression actionExpression -> {
                String actionName = actionExpression.getActionUsage().getActionReference().getName();

                Action action = getActionByName(actionName);

                if (!action.validateArgumentTypes(actionExpression.getActionUsage())) {
                    throw new GenerationImplementationException("Несовместимые типы аргументов для действия " + actionName);
                }

                CodeBlock codeBlock = action.generateCode(actionExpression.getActionUsage());
                yield codeBlock.toString();
            }
            case NullLiteral nullLiteral -> null;
            case null -> null;
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

    private Action getActionByName(String actionName) {
        switch (actionName) {
            case "ADD_ACTION" -> {
                return new ArraySumAction(this);
            }
            default -> {
                log.error("Unknown action: {}", actionName);
                throw new GenerationImplementationException("Unknown action: " + actionName);
            }
        }
    }

}
