package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import com.nvp.orchestrator.model.ModelData;
import com.nvp.orchestrator.model.ModelVariable;
import lombok.extern.slf4j.Slf4j;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.search.strategy.Search;
import org.chocosolver.solver.variables.BoolVar;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.variables.RealVar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.libsl.nodes.*;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.*;
import org.springframework.javapoet.CodeBlock.Builder;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public final class ContractsApiImplementationGenerator extends ApiImplementationGenerator {

    private final Library library;

    public ContractsApiImplementationGenerator(Path generatedProjectPath, Library library) {
        super(generatedProjectPath);
        this.library = library;
    }

    @Override
    protected MethodSpec generateMethodStub(String interfaceName, Method method) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(method.getName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class);

        // Добавляем параметры метода
        for (Parameter parameter : method.getParameters()) {
            methodBuilder.addParameter(TypeName.get(parameter.getParameterizedType()), parameter.getName());
        }

        // Указываем возвращаемый тип метода
        Type returnType = method.getGenericReturnType();
        TypeName returnTypeName = TypeName.get(returnType);
        methodBuilder.returns(returnTypeName);

        // if it has contracts use them to generate method body
        if (library != null) {
            Automaton automaton = getAutomaton(interfaceName, library);
            if (automaton != null) {
                Function function = getFunction(method, automaton);
                if (function != null) {
                    List<Contract> contracts = function.getContracts();
                    if (!contracts.isEmpty()) {
                        List<Contract> requires = getContractList(contracts, ContractKind.REQUIRES);
                        List<Contract> ensures = getContractList(contracts, ContractKind.ENSURES);
                        return generateMethodResponseCodeBlockFromContracts(methodBuilder, returnType, requires, ensures, method.getName(), method.getParameters());
                    }
                }
            }
        }

        // Не найдены контракты - генерация случайных данных
        log.info("No contracts found for method {}", method.getName());
        methodBuilder.addStatement("return $L", generateRandomGeneratedObject(returnType));

        return methodBuilder.build();
    }

    @Override
    protected void generateImplementationForInterface(Class<?> apiInterface) {
        String implClassName = apiInterface.getSimpleName().replaceAll("Api$", "ApiController");

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implClassName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(org.springframework.web.bind.annotation.RestController.class)
                .addSuperinterface(apiInterface);

        // Генерируем методы интерфейса
        for (Method method : apiInterface.getMethods()) {
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(Integer.class, method.getName(), Modifier.PRIVATE).initializer("1");
            classBuilder.addField(fieldBuilder.build());
            classBuilder.addMethod(generateMethodStub(getApiInterfaceName(apiInterface), method));
        }

        String packageName = apiInterface.getPackage().getName();
        saveGeneratedClass(classBuilder, packageName);

        log.info("Сгенерирован класс: {}", implClassName);
    }

    @NotNull
    private static List<Contract> getContractList(List<Contract> contracts, ContractKind requires) {
        return contracts.stream().filter(c -> c.getKind().equals(requires)).toList();
    }

    @Nullable
    private static Function getFunction(Method method, Automaton automaton) {
        return automaton.getFunctions().stream().filter(f -> method.getName().toLowerCase().contains(f.getName().toLowerCase())).findFirst().orElse(null);
    }

    @Nullable
    private static Automaton getAutomaton(String interfaceName, Library library) {
        return library.getAutomata().stream().filter(a -> a.getName().equals(interfaceName)).findFirst().orElse(null);
    }

    private MethodSpec generateMethodResponseCodeBlockFromContracts(
            MethodSpec.Builder methodBuilder, Type returnType, List<Contract> requires, List<Contract> ensures, String methodName, Parameter[] parameters) {
        CodeBlock.Builder cbb = CodeBlock.builder();

        generateCheckRequires(requires, cbb);

        Class<?> returnClass = getReturnClassFromResponseEntity(returnType);

        generateResponseResultBasedOnContracts(cbb, returnClass, ensures, methodName, parameters);

        return methodBuilder.addCode(cbb.build()).build();
    }

    private static void generateCheckRequires(List<Contract> requires, CodeBlock.Builder cbb) {
        if (!requires.isEmpty()) {
            cbb.add("\n// Check requires\n");
            for (Contract require : requires) {
                cbb.beginControlFlow("if (!($L))", contractExpressionToRequires(require));
                cbb.addStatement("throw new $T($S)", IllegalArgumentException.class, "Precondition with " + (require.getName() == null ? "unset name" : require.getName()) + " failed");
                cbb.endControlFlow();
            }
        }
    }

    /**
     * <pre>
     * Extracts the return class from the return type of the method.
     * If the return type is ResponseEntity<T>, then T is returned.
     * Otherwise, an exception is thrown.
     * it is assumed that openapi-generator generates only ResponseEntity<T> return types.</pre>
     **/
    @NotNull
    private static Class<?> getReturnClassFromResponseEntity(Type returnType) {
        if (returnType instanceof ParameterizedType returnClass) {
            if (returnClass.getRawType() == ResponseEntity.class) {
                return (Class<?>) returnClass.getActualTypeArguments()[0];
            }
        }

        throw new GenerationImplementationException("No return class can be inferred for " + returnType.getTypeName());
    }

    private void generateResponseResultBasedOnContracts(CodeBlock.Builder cbb, Class<?> returnClass, List<Contract> ensures, String methodName, Parameter[] parameters) {
        ModelData modelData = new ModelData(returnClass, ensures, parameters);

        createModel(cbb);

        createModelVariables(cbb, modelData);

        generateContracts(cbb, modelData);

        createSolverAndFindSolutions(cbb, methodName, modelData);

        checkIsSolutionExists(cbb);

        restoreObjectWithSolution(cbb, modelData, returnClass);

        returnAnswer(cbb);
    }

    private static void createModel(CodeBlock.Builder cbb) {
        cbb.add("\n// Create model\n");
        cbb.addStatement("$T model = new $T()", Model.class, Model.class);
    }

    private static void generateContracts(CodeBlock.Builder cbb, ModelData modelData) {
        cbb.add("\n// Add contracts\n");
        cbb.add(modelData.generateModelContracts());
    }

    private static void checkIsSolutionExists(CodeBlock.Builder cbb) {
        cbb.add("\n// Check if solution is found\n");
        cbb.beginControlFlow("if (solution == null)");
        cbb.addStatement("throw new $T($S)", IllegalArgumentException.class, "Cannot find solution for the given constraints");
        cbb.endControlFlow();
    }

    private static void returnAnswer(CodeBlock.Builder codeBlockBuilder) {
        codeBlockBuilder.addStatement("return $T.ok(answer)", ResponseEntity.class);
    }

    private static void createSolverAndFindSolutions(Builder cbb, String methodName, ModelData modelData) {
        cbb.add("\n// Create solver and find solution\n");
        cbb.addStatement("$T solver = model.getSolver()", Solver.class);
        cbb.add(setSearchStrategy(modelData));
        cbb.addStatement("$T solution = solver.findSolution()", Solution.class);
        cbb.beginControlFlow("for (int i = 1; i < $L; i++)", methodName);
        cbb.addStatement("solution = solver.findSolution()");
        cbb.endControlFlow();
        cbb.addStatement("$L++", methodName);
        cbb.beginControlFlow("if (solution == null)");
        cbb.addStatement("solver.reset()");
        cbb.addStatement("solution = solver.findSolution()");
        cbb.addStatement("$L = 2", methodName);
        cbb.endControlFlow();
    }

    private static String contractExpressionToRequires(Contract contract) {
        String expression = contract.getExpression().dumpToString();
        Pattern pattern = Pattern.compile("\\.([a-zA-Z]\\w*)");
        Matcher matcher = pattern.matcher(expression);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String field = matcher.group(1);
            String replacement = ".get" + field.substring(0, 1).toUpperCase() + field.substring(1) + "()";
            matcher.appendReplacement(sb, replacement);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Restores the object with the solution found by the solver.
     */
    private void restoreObjectWithSolution(CodeBlock.Builder codeBlockBuilder, ModelData modelData, Class<?> returnClass) {
        codeBlockBuilder.add("\n// Restore object with solution\n");
        codeBlockBuilder.addStatement("$T answer = $L", returnClass, generateRandomGeneratedObject(returnClass));

        modelData.restoreModelContracts(codeBlockBuilder);
    }

    private void createModelVariables(CodeBlock.Builder cbb, ModelData modelData) {
        cbb.add("\n// Create model variables\n");
        for (ModelVariable mv : modelData.getFieldsAffectedByContracts()) {
            if (mv.isParameter()) {
                createModelVariableParameter(cbb, mv, modelData);
            } else {
                createModelVariableResultField(cbb, mv);
            }
        }
    }

    private void createModelVariableParameter(CodeBlock.Builder cbb, ModelVariable modelVariable, ModelData modelData) {
        Class<?> type = modelVariable.type();

        String path = modelData.restorePathOfParameter(modelVariable);

        if (type == Integer.class) {
            cbb.addStatement("$T $L = model.intVar($S, $L)", IntVar.class, modelVariable.name(), modelVariable.name(), path);
        } else if (type == Double.class || type == Float.class) {
            cbb.addStatement("$T $L = model.realVar($S, $L)", RealVar.class, modelVariable.name(), modelVariable.name(), path);
        } else if (type == Boolean.class) {
            cbb.addStatement("$T $L = model.boolVar($S, $L)", BoolVar.class, modelVariable.name(), modelVariable.name(), path);
        } else {
            throw new GenerationImplementationException("Unsupported type " + type.getName());
        }
    }

    private void createModelVariableResultField(CodeBlock.Builder cbb, ModelVariable modelVariable) {
        Class<?> type = modelVariable.type();

        if (type == Integer.class) {
            cbb.addStatement("$T $L = model.intVar($S, $L, $L)", IntVar.class, modelVariable.name(), modelVariable.name(), -1000000, 1000000);
        } else if (type == Double.class || type == Float.class) {
            cbb.addStatement("$T $L = model.realVar($S, $L, $L, 0.01)", RealVar.class, modelVariable.name(), modelVariable.name(), -1000000.0, 1000000.0);
        } else if (type == Boolean.class) {
            cbb.addStatement("$T $L = model.boolVar($S)", BoolVar.class, modelVariable.name(), modelVariable.name());
        } else if (type == String.class) {
            cbb.addStatement("$T $L = new $T()", String.class, modelVariable.name(), String.class);
        } else {
            throw new GenerationImplementationException("Unsupported type " + type.getName());
        }

    }

    private static CodeBlock setSearchStrategy(ModelData modelData) {
        String intVars = modelData.getFieldsAffectedByContracts().stream()
                .filter(mv -> mv.type().equals(Integer.class))
                .map(ModelVariable::name)
                .collect(Collectors.joining(", "));
        String realVars = modelData.getFieldsAffectedByContracts().stream()
                .filter(mv -> List.of(Double.class, Float.class).contains(mv.type()))
                .map(ModelVariable::name)
                .collect(Collectors.joining(", "));
        CodeBlock.Builder cbb = CodeBlock.builder();
        if (!intVars.isEmpty() && !realVars.isEmpty()) {
            cbb.addStatement("solver.setSearch($T.randomSearch(new $T[]{ $L }, new $T().nextInt()), $T.realVarSearch($L))", Search.class, IntVar[].class, intVars, Random.class, Search.class, realVars);
        } else if (!intVars.isEmpty()) {
            cbb.addStatement("solver.setSearch($T.randomSearch(new $T[]{ $L }, new $T().nextInt()))", Search.class, IntVar[].class, intVars, Random.class);
        } else if (!realVars.isEmpty()) {
            cbb.addStatement("solver.setSearch($T.realVarSearch($L))", Search.class, realVars);
        }
        return cbb.build();
    }

}
