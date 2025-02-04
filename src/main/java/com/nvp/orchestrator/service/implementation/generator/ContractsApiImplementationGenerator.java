package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.libsl.nodes.*;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.*;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
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

        // Добавляем заглушку для возвращаемого значения
        methodBuilder.addStatement("return $L", generateRandomMethodResponseCodeBlock(returnType));

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

        // проверка requires - если не прошла - ошибка
        codeBlockBuilder.beginControlFlow("if (!($L))", generateContractsCondition(requires));
        codeBlockBuilder.addStatement("throw new $T($S)", IllegalArgumentException.class, "Precondition failed");
        codeBlockBuilder.endControlFlow();

        Class<?> returnClass = getReturnClassFromResponseEntity(returnType);

        codeBlockBuilder.add(generateResponseResultBasedOnContracts(returnClass, ensures));

        return methodBuilder.addCode(codeBlockBuilder.build()).build();
    }

    private CodeBlock generateResponseResultBasedOnContracts(Class<?> returnClass, List<Contract> ensures) {
        return null;
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

    private static String contractExpressionToDumpString(Contract contract) {
        return contract.getExpression().dumpToString();
    }

    // Генерация случайных данных по типу
    private CodeBlock generateRandomMethodResponseCodeBlock(Type returnType) {
        if (returnType instanceof Class<?> returnClass) {
            // Обработка примитивов и известных типов
            if (returnClass == int.class || returnClass == Integer.class) {
                return CodeBlock.builder().add("new $T().nextInt()", Random.class).build();
            }

            if (returnClass == long.class || returnClass == Long.class) {
                return CodeBlock.builder().add("new $T().nextLong()", Random.class).build();
            }

            if (returnClass == double.class || returnClass == Double.class) {
                return CodeBlock.builder().add("new $T().nextDouble()", Random.class).build();
            }

            if (returnClass == boolean.class || returnClass == Boolean.class) {
                return CodeBlock.builder().add("new $T().nextBoolean()", Random.class).build();
            }

            if (returnClass == String.class) {
                return CodeBlock.builder().add("$S + new $T().nextInt()", "RandomString", Random.class).build();
            }

            if (List.of(LocalDate.class, LocalDateTime.class, ZonedDateTime.class, OffsetDateTime.class).contains(returnClass)) {
                return CodeBlock.builder().add("$T.now()", returnClass).build();
            }

            if (returnClass.isArray()) {
                // Генерация пустого массива
                return CodeBlock.builder()
                        .add("$T.singletonList($L)", java.util.Collections.class, generateRandomMethodResponseCodeBlock(returnClass.getComponentType()))
                        .build();
            }

            if (returnClass.isPrimitive()) {
                return CodeBlock.builder().add("0").build(); // Значение по умолчанию для примитивов
            }

            if (returnClass.isEnum()) {
                Object[] enumConstants = returnClass.getEnumConstants();
                return CodeBlock.builder().add("$T.$L", returnClass, enumConstants[0].toString().toUpperCase()).build();
            }

            if (!returnClass.isInterface()) {
                // Используем публичный конструктор с наибольшим количеством параметров
                return CodeBlock.builder().add(generateConstructorValue(returnClass)).build();
            }
        }

        if (returnType instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();

            // Обработка ResponseEntity
            if (rawType == ResponseEntity.class) {
                Type responseType = parameterizedType.getActualTypeArguments()[0];
                if (responseType == Void.class) {
                    return CodeBlock.builder().add("$T.ok().build()", org.springframework.http.ResponseEntity.class).build();
                }
                return CodeBlock.builder().add("$T.ok($L)", org.springframework.http.ResponseEntity.class, generateRandomMethodResponseCodeBlock(responseType)).build();
            }

            // Обработка коллекций и мап
            if (rawType == List.class || rawType == ArrayList.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().add("$T.singletonList($L)", java.util.Collections.class, generateRandomMethodResponseCodeBlock(elementType)).build();
            }

            if (rawType == Set.class || rawType == HashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().add("$T.singleton($L)", java.util.Collections.class, generateRandomMethodResponseCodeBlock(elementType)).build();
            }

            if (rawType == Map.class || rawType == HashMap.class) {
                Type keyType = parameterizedType.getActualTypeArguments()[0];
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                return CodeBlock.builder().add("$T.singletonMap($L, $L)", java.util.Collections.class, generateRandomMethodResponseCodeBlock(keyType), generateRandomMethodResponseCodeBlock(valueType)).build();
            }
        }

        log.warn("Не удалось сгенерировать значение для типа: {}", returnType);
        return CodeBlock.builder().add("null").build();
    }

    // Генерация значения с использованием случайного конструктора
    private CodeBlock generateConstructorValue(Class<?> customClass) {
        java.lang.reflect.Constructor<?>[] constructors = customClass.getDeclaredConstructors();

        // Фильтруем только доступные конструкторы
        List<java.lang.reflect.Constructor<?>> accessibleConstructors = new ArrayList<>(Arrays.asList(constructors));

        if (accessibleConstructors.isEmpty()) {
            return CodeBlock.builder().add("null").build();
        }

        // Выбираем случайный конструктор с максимальным количеством параметров для public конструкторов
        java.lang.reflect.Constructor<?> randomConstructor = getConstructor(customClass, accessibleConstructors);

        // Генерируем параметры для конструктора
        String constructorArgs = getConstructorArgs(randomConstructor);

        // Возвращаем строку для вызова конструктора
        return CodeBlock.builder().add("new $T($L)", customClass, constructorArgs).build();
    }

    @NotNull
    private String getConstructorArgs(java.lang.reflect.Constructor<?> randomConstructor) {
        return Arrays.stream(randomConstructor.getParameters())
                .map(Parameter::getParameterizedType)
                .map(this::generateRandomMethodResponseCodeBlock)
                .map(CodeBlock::toString)
                .collect(Collectors.joining(", "));
    }

    private static java.lang.reflect.Constructor<?> getConstructor(Class<?> customClass, List<java.lang.reflect.Constructor<?>> accessibleConstructors) {
        return accessibleConstructors.stream()
                .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow(
                        () -> new GenerationImplementationException("No public constructors found for class " + customClass.getName())
                );
    }
}
