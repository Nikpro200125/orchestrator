package com.nvp.orchestrator.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.research.libsl.nodes.*;
import org.reflections.Reflections;
import org.reflections.scanners.Scanner;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.*;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
public class ApiImplementationGenerator {

    private final Path generatedProjectPath;

    /**
     * Генерация реализаций интерфейсов API.
     */
    public void generate(Library library) throws IOException {
        // Получаем все классы из пакета org.openapitools.api
        Collection<Class<?>> apiClasses = getClassesFromPackage();
        // Ищем интерфейсы
        for (Class<?> apiInterface : apiClasses) {
            if (apiInterface.isInterface() && apiInterface.getSimpleName().endsWith("Api")) {
                generateImplementationForInterface(apiInterface, library);
            }
        }

    }

    private Collection<Class<?>> getClassesFromPackage() throws MalformedURLException {
        URLClassLoader urlClassLoader = new URLClassLoader(new URL[]{generatedProjectPath.resolve("target/classes").toUri().toURL()});
        Path classesPath = generatedProjectPath.resolve("target/classes/org/openapitools/api");
        URL url = classesPath.toUri().toURL();
        Scanner scanner = Scanners.SubTypes.filterResultsBy(s -> true);
        Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .setUrls(url)
                        .setScanners(scanner)
                        .setClassLoaders(new URLClassLoader[]{urlClassLoader})
        );
        return reflections.getSubTypesOf(Object.class);
    }

    /**
     * Генерация реализации для интерфейса API.
     */
    private void generateImplementationForInterface(Class<?> apiInterface, Library library) throws IOException {
        String implClassName = apiInterface.getSimpleName().replaceAll("Api$", "ApiController");
        String packageName = apiInterface.getPackage().getName();

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implClassName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(org.springframework.web.bind.annotation.RestController.class)
                .addSuperinterface(apiInterface);

        // Генерируем методы интерфейса
        for (Method method : apiInterface.getMethods()) {
            classBuilder.addMethod(generateMethodStub(getApiInterfaceName(apiInterface), method, library));
        }

        // Создаём файл Java
        TypeSpec implType = classBuilder.build();
        JavaFile javaFile = JavaFile.builder(packageName, implType).build();

        // Сохраняем файл в src/main/java
        Path outputDir = generatedProjectPath.resolve("src/main/java");
        javaFile.writeTo(outputDir);

        log.info("Сгенерирован класс: {}", implClassName);
    }

    @NotNull
    private static String getApiInterfaceName(Class<?> apiInterface) {
        return apiInterface.getSimpleName().substring(0, apiInterface.getSimpleName().length() - 3);
    }

    /**
     * Генерация заглушки для метода.
     */
    private MethodSpec generateMethodStub(String interfaceName, Method method, Library library) {
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
                        return generateMethodStatementFromContracts(methodBuilder, returnType, requires, ensures);
                    }
                }
            }
        }

        // Добавляем заглушку для возвращаемого значения
        methodBuilder.addStatement("return $L", generateRandomValueCodeBlock(returnType));

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

    private MethodSpec generateMethodStatementFromContracts(MethodSpec.Builder methodBuilder, Type returnType, List<Contract> requires, List<Contract> ensures) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();
        codeBlockBuilder.beginControlFlow("if (!($L))", generateRequiresContractsIfStatement(requires));
        codeBlockBuilder.addStatement("throw new $T($S)", IllegalArgumentException.class, "Precondition failed");
        codeBlockBuilder.endControlFlow();
        codeBlockBuilder.addStatement("return $L", generateRandomValueCodeBlock(returnType));
        return methodBuilder.addCode(codeBlockBuilder.build()).build();
    }

    private static CodeBlock generateRequiresContractsIfStatement(List<Contract> requires) {
        CodeBlock.Builder codeBlockBuilder = CodeBlock.builder();
        for (int i = 0; i < requires.size(); i++) {
            codeBlockBuilder.add("($L)", generateRequiresContractCodeBlock(requires.get(i)));
            if (i < requires.size() - 1) {
                codeBlockBuilder.add(" && ");
            }
        }
        return codeBlockBuilder.build();
    }

    private static String generateRequiresContractCodeBlock(Contract contract) {
        return contract.getExpression().dumpToString();
    }

    // Генерация случайных данных по типу
    private CodeBlock generateRandomValueCodeBlock(Type returnType) {
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
                        .add("$T.singletonList($L)", java.util.Collections.class, generateRandomValueCodeBlock(returnClass.getComponentType()))
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
                return CodeBlock.builder().add(generateRandomConstructorValue(returnClass)).build();
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
                return CodeBlock.builder().add("$T.ok($L)", org.springframework.http.ResponseEntity.class, generateRandomValueCodeBlock(responseType)).build();
            }

            // Обработка коллекций и карт
            if (rawType == List.class || rawType == ArrayList.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().add("$T.singletonList($L)", java.util.Collections.class, generateRandomValueCodeBlock(elementType)).build();
            }

            if (rawType == Set.class || rawType == HashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().add("$T.singleton($L)", java.util.Collections.class, generateRandomValueCodeBlock(elementType)).build();
            }

            if (rawType == Map.class || rawType == HashMap.class) {
                Type keyType = parameterizedType.getActualTypeArguments()[0];
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                return CodeBlock.builder().add("$T.singletonMap($L, $L)", java.util.Collections.class, generateRandomValueCodeBlock(keyType), generateRandomValueCodeBlock(valueType)).build();
            }
        }

        log.warn("Не удалось сгенерировать значение для типа: {}", returnType);
        return CodeBlock.builder().add("null").build();
    }

    // Генерация значения с использованием случайного конструктора
    private CodeBlock generateRandomConstructorValue(Class<?> customClass) {
        Constructor<?>[] constructors = customClass.getDeclaredConstructors();

        // Фильтруем только доступные конструкторы
        List<Constructor<?>> accessibleConstructors = new ArrayList<>(Arrays.asList(constructors));

        if (accessibleConstructors.isEmpty()) {
            return CodeBlock.builder().add("null").build();
        }

        // Выбираем случайный конструктор с максимальным количеством параметров для public конструкторов
        Constructor<?> randomConstructor = accessibleConstructors.stream()
                .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow(
                        () -> new IllegalStateException("No public constructors found for class " + customClass.getName())
                );

        // Генерируем параметры для конструктора
        String constructorArgs = Arrays.stream(randomConstructor.getParameters())
                .map(Parameter::getParameterizedType)
                .map(this::generateRandomValueCodeBlock)
                .map(CodeBlock::toString)
                .collect(Collectors.joining(", "));

        // Возвращаем строку для вызова конструктора
        return CodeBlock.builder().add("new $T($L)", customClass, constructorArgs).build();
    }

}