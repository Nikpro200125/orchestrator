package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import com.nvp.orchestrator.exceptions.GenerationServiceException;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;
import org.reflections.scanners.Scanner;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.CodeBlock;
import org.springframework.javapoet.JavaFile;
import org.springframework.javapoet.MethodSpec;
import org.springframework.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.lang.reflect.*;
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

@Slf4j
@RequiredArgsConstructor
public sealed abstract class ApiImplementationGenerator permits ContractsApiImplementationGenerator, RandomApiImplementationGenerator {

    private final Path generatedProjectPath;

    @SneakyThrows
    public void generate() {
        Collection<Class<?>> apiClasses = getClassesFromPackage();

        apiClasses.stream()
                .filter(apiInterface -> apiInterface.isInterface() && apiInterface.getSimpleName().endsWith("Api"))
                .forEach(this::generateImplementationForInterface);
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

    private void generateImplementationForInterface(Class<?> apiInterface) {
        String implClassName = apiInterface.getSimpleName().replaceAll("Api$", "ApiController");

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implClassName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(org.springframework.web.bind.annotation.RestController.class)
                .addSuperinterface(apiInterface);

        // Генерируем методы интерфейса
        for (Method method : apiInterface.getMethods()) {
            classBuilder.addMethod(generateMethodStub(getApiInterfaceName(apiInterface), method));
        }

        String packageName = apiInterface.getPackage().getName();
        saveGeneratedClass(classBuilder, packageName);

        log.info("Сгенерирован класс: {}", implClassName);
    }

    private void saveGeneratedClass(TypeSpec.Builder classBuilder, String packageName) {
        TypeSpec implType = classBuilder.build();
        JavaFile javaFile = JavaFile.builder(packageName, implType).build();

        // Сохраняем файл в src/main/java
        Path outputDir = generatedProjectPath.resolve("src/main/java");
        try {
            javaFile.writeTo(outputDir);
        } catch (Exception e) {
            log.error("Failed to write Java file", e);
            throw new GenerationServiceException("Failed to write Java file");
        }
    }

    abstract protected MethodSpec generateMethodStub(String apiInterfaceName, Method method);

    @NotNull
    private static String getApiInterfaceName(Class<?> apiInterface) {
        return apiInterface.getSimpleName().substring(0, apiInterface.getSimpleName().length() - 3);
    }

    // Генерация случайных данных по типу
    protected CodeBlock generateRandomGeneratedObject(Type returnType) {
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
                        .add("$T.singletonList($L)", java.util.Collections.class, generateRandomGeneratedObject(returnClass.getComponentType()))
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
                return CodeBlock.builder().add("$T.ok($L)", org.springframework.http.ResponseEntity.class, generateRandomGeneratedObject(responseType)).build();
            }

            // Обработка коллекций и мап
            if (rawType == List.class || rawType == ArrayList.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().add("$T.singletonList($L)", java.util.Collections.class, generateRandomGeneratedObject(elementType)).build();
            }

            if (rawType == Set.class || rawType == HashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().add("$T.singleton($L)", java.util.Collections.class, generateRandomGeneratedObject(elementType)).build();
            }

            if (rawType == Map.class || rawType == HashMap.class) {
                Type keyType = parameterizedType.getActualTypeArguments()[0];
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                return CodeBlock.builder().add("$T.singletonMap($L, $L)", java.util.Collections.class, generateRandomGeneratedObject(keyType), generateRandomGeneratedObject(valueType)).build();
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
                .map(this::generateRandomGeneratedObject)
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
