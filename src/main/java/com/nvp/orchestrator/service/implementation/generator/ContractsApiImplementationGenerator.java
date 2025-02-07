package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import com.nvp.orchestrator.model.ModelData;
import com.nvp.orchestrator.model.ModelVariable;
import lombok.extern.slf4j.Slf4j;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.Solver;
import org.chocosolver.solver.variables.IntVar;
import org.chocosolver.solver.variables.RealVar;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.libsl.nodes.*;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.*;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.*;

import static java.util.regex.Matcher.quoteReplacement;

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

        // if have contracts use them to generate method body
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
        log.error("No contracts found for method {}", method.getName());
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
            FieldSpec.Builder fieldBuilder = FieldSpec.builder(Integer.class, method.getName(), Modifier.PRIVATE)
                    .initializer("1");
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

    private MethodSpec generateMethodResponseCodeBlockFromContracts(MethodSpec.Builder methodBuilder, Type returnType, List<Contract> requires, List<Contract> ensures, String methodName, Parameter[] parameters) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();

        codeBlockBuilder.beginControlFlow("if (!($L))", generateContractsCondition(requires));
        codeBlockBuilder.addStatement("throw new $T($S)", IllegalArgumentException.class, "Precondition failed");
        codeBlockBuilder.endControlFlow();

        Class<?> returnClass = getReturnClassFromResponseEntity(returnType);

        codeBlockBuilder.add(generateResponseResultBasedOnContracts(returnClass, ensures, methodName, parameters));

        return methodBuilder.addCode(codeBlockBuilder.build()).build();
    }

    @NotNull
    private static Class<?> getReturnClassFromResponseEntity(Type returnType) {
        if (returnType instanceof ParameterizedType returnClass) {
            if (returnClass.getRawType() == ResponseEntity.class) {
                return (Class<?>) returnClass.getActualTypeArguments()[0];
            }
        }

        throw new GenerationImplementationException("No return class can be inferred for " + returnType.getTypeName());
    }

    private static CodeBlock generateContractsCondition(List<Contract> requires) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();
        for (int i = 0; i < requires.size(); i++) {
            codeBlockBuilder.add("($L)", contractExpressionToDumpString(requires.get(i)));
            if (i < requires.size() - 1) {
                codeBlockBuilder.add(" && ");
            }
        }
        return codeBlockBuilder.build();
    }

    private CodeBlock generateResponseResultBasedOnContracts(Class<?> returnClass, List<Contract> ensures, String methodName, Parameter[] parameters) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();
        ModelData modelData = new ModelData(returnClass, ensures, parameters);

        codeBlockBuilder.add("\n// Create model\n");
        codeBlockBuilder.addStatement("$T model = new $T()", Model.class, Model.class);

        // generate ensures conditions
        createModelVariables(codeBlockBuilder, modelData);
        codeBlockBuilder.add("\n// Add contracts\n");
        codeBlockBuilder.add(modelData.getModelContracts());

        // create solver and find solution
        codeBlockBuilder.add("\n// Create solver and find solution\n");
        codeBlockBuilder.addStatement("$T solver = model.getSolver()", Solver.class);
        codeBlockBuilder.addStatement("$T solution = solver.findSolution()", Solution.class);
        codeBlockBuilder.beginControlFlow("for (int i = 1; i < $L; i++)", methodName);
        codeBlockBuilder.addStatement("solution = solver.findSolution()");
        codeBlockBuilder.endControlFlow();
        codeBlockBuilder.addStatement("$L++", methodName);
        codeBlockBuilder.beginControlFlow("if (solution == null)");
        codeBlockBuilder.addStatement("solver.reset()");
        codeBlockBuilder.addStatement("solution = solver.findSolution()");
        codeBlockBuilder.addStatement("$L = 2", methodName);
        codeBlockBuilder.endControlFlow();

        // check if solution is found
        codeBlockBuilder.add("\n// Check if solution is found\n");
        codeBlockBuilder.beginControlFlow("if (solution == null)");
        codeBlockBuilder.addStatement("throw new $T($S)", IllegalArgumentException.class, "Cannot find solution for the given constraints");
        codeBlockBuilder.endControlFlow();

        // generate return statement
        codeBlockBuilder.add("\n// Restore object with solution\n");
        restoreObjectWithSolution(codeBlockBuilder, modelData, returnClass);

        // return restored object
        codeBlockBuilder.addStatement("return $T.ok(answer)", ResponseEntity.class);

        return codeBlockBuilder.build();
    }

    private static String contractExpressionToDumpString(Contract contract) {
        return contract.getExpression().dumpToString();
    }

    private void restoreObjectWithSolution(CodeBlock.Builder codeBlockBuilder, ModelData modelData, Class<?> returnClass) {
        // create object with random values and then restore it with solution
        codeBlockBuilder.addStatement("$T answer = $L", returnClass, generateRandomGeneratedObject(returnClass));

        modelData.restoreModelContracts(codeBlockBuilder);
    }

    private void createModelVariables(CodeBlock.Builder codeBlockBuilder, ModelData modelData) {
        codeBlockBuilder.add("\n// Create model variables\n");
        for (ModelVariable mv : modelData.getFieldsAffectedByContracts()) {
            ModelVariable mp = modelData.isParameter(mv);
            if (mp != null) {
                createModelVariableParameter(codeBlockBuilder, mv, mp);
            } else {
                createModelVariable(codeBlockBuilder, mv);
            }
        }
    }

    private void createModelVariableParameter(CodeBlock.Builder codeBlockBuilder, ModelVariable modelVariable, ModelVariable modelParameter) {
        Class<?> type = modelVariable.type();

        if (type == Integer.class) {
            codeBlockBuilder.addStatement("$T $L = model.intVar($S, $L)", IntVar.class, modelVariable.name(), modelVariable.name(), modelParameter.name());
        } else if (type == Double.class) {
            codeBlockBuilder.addStatement("$T $L = model.realVar($S, $L)", RealVar.class, modelVariable.name(), modelVariable.name(), modelParameter.name());
        } else {
            throw new GenerationImplementationException("Unsupported type " + type.getName());
        }

    }

    private void createModelVariable(CodeBlock.Builder codeBlockBuilder, ModelVariable modelVariable) {
        Class<?> type = modelVariable.type();

        if (type == Integer.class) {
            codeBlockBuilder.addStatement("$T $L = model.intVar($S, $L, $L)", IntVar.class, modelVariable.name(), modelVariable.name(), -1000000, 1000000);
        } else if (type == Double.class) {
            codeBlockBuilder.addStatement("$T $L = model.realVar($S, $L, $L, 0.01)", RealVar.class, modelVariable.name(), modelVariable.name(), -1000000.0, 1000000.0);
        } else {
            throw new GenerationImplementationException("Unsupported type " + type.getName());
        }

    }

}
