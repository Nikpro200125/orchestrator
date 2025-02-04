package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationImplementationException;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
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
public final class RandomApiImplementationGenerator extends ApiImplementationGenerator {

    public RandomApiImplementationGenerator(Path generatedProjectPath) {
        super(generatedProjectPath);
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

        // Добавляем заглушку для возвращаемого значения
        methodBuilder.addStatement("return $L", generateRandomMethodResponseCodeBlock(returnType));

        return methodBuilder.build();
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
