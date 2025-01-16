package com.nvp.orchestrator.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.*;

import javax.lang.model.element.Modifier;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.IntStream;

@RequiredArgsConstructor
@Slf4j
public class ApiImplementationGenerator {

    private final Path generatedProjectPath;
    private URLClassLoader urlClassLoader;

    /**
     * Генерация реализаций интерфейсов API.
     */
    public void generate() throws IOException {

        // Получаем все классы из пакета org.openapitools.api
        Collection<Class<?>> apiClasses = getClassesFromPackage();
        // Ищем интерфейсы
        for (Class<?> apiInterface : apiClasses) {
            if (apiInterface.isInterface() && apiInterface.getSimpleName().endsWith("Api")) {
                generateImplementationForInterface(apiInterface);
            }
        }

    }

    private Collection<Class<?>> getClassesFromPackage() throws MalformedURLException {
        urlClassLoader = new URLClassLoader(new URL[]{generatedProjectPath.resolve("target/classes").toUri().toURL()});
        Path classesPath = generatedProjectPath.resolve("target/classes/org/openapitools/api");
        URL url = classesPath.toUri().toURL();
        Reflections reflections = new Reflections(
                new ConfigurationBuilder()
                        .setUrls(url)
                        .setScanners(Scanners.SubTypes.filterResultsBy(s -> true))
                        .setClassLoaders(new URLClassLoader[]{urlClassLoader})
        );
        return reflections.getSubTypesOf(Object.class);
    }

    /**
     * Генерация реализации для интерфейса API.
     */
    private void generateImplementationForInterface(Class<?> apiInterface) throws IOException {
        String implClassName = apiInterface.getSimpleName().replaceAll("Api$", "ApiController");
        String packageName = apiInterface.getPackage().getName();

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(implClassName)
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(org.springframework.web.bind.annotation.RestController.class)
                .addSuperinterface(apiInterface);

        // Генерируем методы интерфейса
        for (Method method : apiInterface.getMethods()) {
            classBuilder.addMethod(generateMethodStub(method));
        }

        // Создаём файл Java
        TypeSpec implType = classBuilder.build();
        JavaFile javaFile = JavaFile.builder(packageName, implType).build();

        // Сохраняем файл в src/main/java
        Path outputDir = generatedProjectPath.resolve("src/main/java");
        javaFile.writeTo(outputDir);

        log.info("Сгенерирован класс: {}", implClassName);
    }

    /**
     * Генерация заглушки для метода.
     */
    private MethodSpec generateMethodStub(Method interfaceMethod) {
        MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder(interfaceMethod.getName())
                .addModifiers(Modifier.PUBLIC)
                .addAnnotation(Override.class);

        // Добавляем параметры метода
        for (Parameter parameter : interfaceMethod.getParameters()) {
            methodBuilder.addParameter(TypeName.get(parameter.getParameterizedType()), parameter.getName());
        }

        // Указываем возвращаемый тип метода
        Type returnType = interfaceMethod.getGenericReturnType();
        TypeName returnTypeName = TypeName.get(returnType);
        methodBuilder.returns(returnTypeName);

        // Добавляем заглушку для возвращаемого значения
        methodBuilder.addStatement("return $L", generateRandomValueCodeBlock(returnType));

        return methodBuilder.build();
    }

    // Генерация случайных данных по типу
    private CodeBlock generateRandomValueCodeBlock(Type returnType) {
        if (returnType instanceof Class<?> returnClass) {
            // Обработка примитивов и известных типов
            if (returnClass == int.class || returnClass == Integer.class) {
                return CodeBlock.builder().add("new $T().nextInt()", Random.class).build();
            } else if (returnClass == long.class || returnClass == Long.class) {
                return CodeBlock.builder().addStatement("new $T().nextLong()", Random.class).build();
            } else if (returnClass == double.class || returnClass == Double.class) {
                return CodeBlock.builder().addStatement("new $T().nextDouble()", Random.class).build();
            } else if (returnClass == boolean.class || returnClass == Boolean.class) {
                return CodeBlock.builder().addStatement("new $T().nextBoolean()", Random.class).build();
            } else if (returnClass == String.class) {
                return CodeBlock.builder().addStatement("$S + new $T().nextInt()", "RandomString", Random.class).build();
            } else if (returnClass.isArray()) {
                // Генерация пустого массива
                return CodeBlock.builder()
                        .addStatement("$T.range(0, new $T().nextInt(10)).forEach(i -> names.add($L)).toList()", IntStream.class, Random.class, generateRandomValueCodeBlock(returnClass.getComponentType()))
                        .build();
            } else if (returnClass.isPrimitive()) {
                return CodeBlock.builder().addStatement("0").build(); // Значение по умолчанию для примитивов
            } else if (returnClass.isEnum()) {
                Object[] enumConstants = returnClass.getEnumConstants();
//                return enumConstants != null && enumConstants.length > 0
//                        ? returnClass.getSimpleName() + "." + enumConstants[0]
//                        : "null";
                return CodeBlock.builder().addStatement("$T.$L", returnClass, enumConstants[0]).build();
            } else if (!returnClass.isInterface()) {
                // Используем случайный конструктор
                return CodeBlock.builder().addStatement(generateRandomConstructorValue(returnClass)).build();
            }
        } else if (returnType instanceof java.lang.reflect.ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();

            // Обработка ResponseEntity
            if (rawType == ResponseEntity.class) {
                Type responseType = parameterizedType.getActualTypeArguments()[0];
                if (responseType == Void.class) {
                    return CodeBlock.builder().addStatement("$T.ok().build()", org.springframework.http.ResponseEntity.class).build();
                }
                return CodeBlock.builder().add("$T.ok($L)", org.springframework.http.ResponseEntity.class, generateRandomValueCodeBlock(responseType)).build();
            }

            // Обработка коллекций и карт
            if (rawType == List.class || rawType == ArrayList.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().addStatement("$T.singletonList($L)", java.util.Collections.class, generateRandomValueCodeBlock(elementType)).build();
            } else if (rawType == Set.class || rawType == HashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return CodeBlock.builder().addStatement("$T.singleton($L)", java.util.Collections.class, generateRandomValueCodeBlock(elementType)).build();
            } else if (rawType == Map.class || rawType == HashMap.class) {
                Type keyType = parameterizedType.getActualTypeArguments()[0];
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                return CodeBlock.builder().addStatement("$T.singletonMap($L, $L)", java.util.Collections.class, generateRandomValueCodeBlock(keyType), generateRandomValueCodeBlock(valueType)).build();
            }
        }

        return CodeBlock.builder().addStatement("null").build();
    }

    // Генерация значения с использованием случайного конструктора
    private CodeBlock generateRandomConstructorValue(Class<?> customClass) {
        Constructor<?>[] constructors = customClass.getDeclaredConstructors();

        // Фильтруем только доступные конструкторы
        List<Constructor<?>> accessibleConstructors = new ArrayList<>(Arrays.asList(constructors));

        if (accessibleConstructors.isEmpty()) {
            return CodeBlock.builder().addStatement("null").build();
        }

        // Выбираем случайный конструктор с максимальным количеством параметров
        Constructor<?> randomConstructor = accessibleConstructors.stream().max(Comparator.comparingInt(Constructor::getParameterCount)).get();
        // Генерируем параметры для конструктора
        StringBuilder constructorArgs = new StringBuilder();
        for (Parameter parameter : randomConstructor.getParameters()) {
            if (!constructorArgs.isEmpty()) {
                constructorArgs.append(", ");
            }
            constructorArgs.append(generateRandomValueCodeBlock(parameter.getParameterizedType()));
        }

        // Возвращаем строку для вызова конструктора
        return CodeBlock.builder().addStatement("new $T($L)", customClass, constructorArgs).build();
    }

}