package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import com.nvp.orchestrator.model.ModelData;
import lombok.extern.slf4j.Slf4j;
import org.chocosolver.solver.Model;
import org.chocosolver.solver.Solution;
import org.chocosolver.solver.Solver;
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
                        return generateMethodResponseCodeBlockFromContracts(methodBuilder, returnType, requires, ensures);
                    }
                }
            }
        }

        // Не найдены контракты - генерация случайных данных
        log.error("No contracts found for method {}", method.getName());
        methodBuilder.addStatement("return $L", generateRandomGeneratedObject(returnType));

        return methodBuilder.build();
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

    private MethodSpec generateMethodResponseCodeBlockFromContracts(MethodSpec.Builder methodBuilder, Type returnType, List<Contract> requires, List<Contract> ensures) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();

        codeBlockBuilder.beginControlFlow("if (!($L))", generateContractsCondition(requires));
        codeBlockBuilder.addStatement("throw new $T($S)", IllegalArgumentException.class, "Precondition failed");
        codeBlockBuilder.endControlFlow();

        Class<?> returnClass = getReturnClassFromResponseEntity(returnType);

        codeBlockBuilder.add(generateResponseResultBasedOnContracts(returnClass, ensures));

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

    private CodeBlock generateResponseResultBasedOnContracts(Class<?> returnClass, List<Contract> ensures) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();

        codeBlockBuilder.addStatement("$T model = new $T()", Model.class, Model.class);

        // generate ensures conditions
        ModelData modelData = new ModelData(returnClass, ensures);

        codeBlockBuilder.addStatement("$T solver = model.getSolver()", Solver.class);
        codeBlockBuilder.addStatement("$T solution = solver.findSolution()", Solution.class);

        // check if solution is found
        codeBlockBuilder.beginControlFlow("if (solution == null)");
        codeBlockBuilder.addStatement("throw new $T($S)", IllegalArgumentException.class, "Cannot find solution for the given constraints");
        codeBlockBuilder.endControlFlow();

        // generate return statement

        return codeBlockBuilder.build();
    }

    private static String contractExpressionToDumpString(Contract contract) {
        return contract.getExpression().dumpToString();
    }

}
