package com.nvp.orchestrator.service.implementation.generator;

import com.github.curiousoddman.rgxgen.RgxGen;
import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import com.nvp.orchestrator.exceptions.GenerationServiceException;
import jakarta.validation.constraints.NotNull;
import kotlin.Pair;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;
import org.reflections.scanners.Scanner;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.*;
import org.springframework.javapoet.MethodSpec.Builder;

import javax.lang.model.element.Modifier;
import java.io.Closeable;
import java.io.IOException;
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
import java.util.stream.IntStream;

@Slf4j
@RequiredArgsConstructor
public sealed abstract class ApiImplementationGenerator implements Closeable permits ContractsApiImplementationGenerator, RandomApiImplementationGenerator {

    protected static final int MAX_COLLECTION_SIZE = 10;
    protected final Path generatedProjectPath;
    protected URLClassLoader urlClassLoader;

    @SneakyThrows
    public void generate() {
        Collection<Class<?>> apiClasses = getClassesFromPackage();

        apiClasses.stream()
                .filter(apiInterface -> apiInterface.isInterface() && apiInterface.getSimpleName().endsWith("Api"))
                .forEach(this::generateImplementationForInterface);
    }

    protected static Pair<Builder, Type> prepareSignature(Method method) {
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

        return new Pair<>(methodBuilder, returnType);
    }

    private Collection<Class<?>> getClassesFromPackage() throws MalformedURLException {
        urlClassLoader = new URLClassLoader(new URL[]{generatedProjectPath.resolve("target/classes").toUri().toURL()});
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

    abstract protected void generateImplementationForInterface(Class<?> apiInterface);

    protected void saveGeneratedClass(TypeSpec.Builder classBuilder, String packageName) {
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

    @NotNull
    protected static String getApiInterfaceName(Class<?> apiInterface) {
        return apiInterface.getSimpleName().substring(0, apiInterface.getSimpleName().length() - 3);
    }

    protected CodeBlock generateRandomGeneratedObject(Type returnType) {
        return generateRandomGeneratedObject(returnType, 0);
    }

    // Генерация случайных данных по типу
    private CodeBlock generateRandomGeneratedObject(Type returnType, int depth) {
        if (returnType instanceof Class<?> returnClass) {
            // Обработка примитивов и известных типов
            if (returnClass == Integer.class) {
                return CodeBlock.builder().add("new $T().nextInt(1_000_000)", Random.class).build();
            }

            if (returnClass == Long.class) {
                return CodeBlock.builder().add("new $T().nextLong(1_000_000)", Random.class).build();
            }

            if (returnClass == Double.class) {
                return CodeBlock.builder().add("new $T().nextDouble() * 1_000_000", Random.class).build();
            }

            if (returnClass == Float.class) {
                return CodeBlock.builder().add("new $T().nextFloat() * 1_000_000", Random.class).build();
            }

            if (returnClass == Boolean.class) {
                return CodeBlock.builder().add("new $T().nextBoolean()", Random.class).build();
            }

            if (returnClass == String.class) {
                return CodeBlock.builder().add("$T.parse($S).generate()", RgxGen.class, "[a-zA-Z0-9]{0,10}").build();
            }

            if (List.of(LocalDate.class, LocalDateTime.class, ZonedDateTime.class, OffsetDateTime.class).contains(returnClass)) {
                return CodeBlock.builder().add("$T.now()", returnClass).build();
            }

            if (returnClass.isEnum()) {
                Object[] enumConstants = returnClass.getEnumConstants();
                return CodeBlock.builder().add("$T.$L", returnClass, enumConstants[0].toString().toUpperCase()).build();
            }

            if (!returnClass.isInterface()) {
                // Используем публичный конструктор с наибольшим количеством параметров
                return generateConstructorValue(returnClass);
            }
        }

        if (returnType instanceof ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();

            if (rawType == ResponseEntity.class) {
                Type responseType = parameterizedType.getActualTypeArguments()[0];
                if (responseType == Void.class) {
                    return CodeBlock.builder().add("$T.ok().build()", ResponseEntity.class).build();
                }
                return CodeBlock.builder().add("$T.ok($L)", ResponseEntity.class, generateRandomGeneratedObject(responseType)).build();
            }

            if (rawType == List.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder()
                        .add("$T.range(0, new $T().nextInt($L)).mapToObj($L -> $L).collect($T.toList())",
                                IntStream.class,
                                Random.class,
                                depth == 0 ? MAX_COLLECTION_SIZE : "1, " + MAX_COLLECTION_SIZE,
                                "_i".repeat(depth + 1),
                                generateRandomGeneratedObject(elementType, depth + 1),
                                Collectors.class
                        )
                        .build();
            }

            if (rawType == Map.class) {
                Type keyType = parameterizedType.getActualTypeArguments()[0];
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                return CodeBlock.builder()
                        .add("$T.range(0, new $T().nextInt($L)).mapToObj($L -> $L).distinct().collect($T.toMap($L -> $L, $L -> $L))",
                                IntStream.class,
                                Random.class,
                                depth == 0 ? MAX_COLLECTION_SIZE : "1, " + MAX_COLLECTION_SIZE,
                                "_Map_i".repeat(depth + 1),
                                generateRandomGeneratedObject(keyType, depth + 1),
                                Collectors.class,
                                "_Map_left_i".repeat(depth + 1),
                                "_Map_left_i".repeat(depth + 1),
                                "_Map_right_i".repeat(depth + 1),
                                generateRandomGeneratedObject(valueType, depth + 1)
                        )
                        .build();
            }
        }

        log.warn("Не удалось сгенерировать значение для типа: {}", returnType);
        return CodeBlock.builder().add("null").build();
    }

    // Генерация значения с использованием случайного конструктора
    private CodeBlock generateConstructorValue(Class<?> customClass) {
        Constructor<?>[] constructors = customClass.getDeclaredConstructors();

        // Фильтруем только доступные конструкторы
        List<Constructor<?>> accessibleConstructors = new ArrayList<>(Arrays.asList(constructors));

        if (accessibleConstructors.isEmpty()) {
            return CodeBlock.builder().add("null").build();
        }

        // Выбираем случайный конструктор с максимальным количеством параметров для public конструкторов
        Constructor<?> randomConstructor = getConstructor(customClass, accessibleConstructors);

        // Генерируем параметры для конструктора
        String constructorArgs = getConstructorArgs(randomConstructor);

        // Возвращаем строку для вызова конструктора
        return CodeBlock.builder().add("new $T($L)", customClass, constructorArgs).build();
    }

    @NotNull
    private String getConstructorArgs(Constructor<?> randomConstructor) {
        return Arrays.stream(randomConstructor.getParameters())
                .map(Parameter::getParameterizedType)
                .map(this::generateRandomGeneratedObject)
                .map(CodeBlock::toString)
                .collect(Collectors.joining(", "));
    }

    private static Constructor<?> getConstructor(Class<?> customClass, List<Constructor<?>> accessibleConstructors) {
        return accessibleConstructors.stream()
                .filter(constructor -> java.lang.reflect.Modifier.isPublic(constructor.getModifiers()))
                .max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow(
                        () -> new GenerationImplementationException("No public constructors found for class " + customClass.getName())
                );
    }

    @Override
    public void close() throws IOException {
        if (urlClassLoader != null) {
            urlClassLoader.close();
        }
    }
}
