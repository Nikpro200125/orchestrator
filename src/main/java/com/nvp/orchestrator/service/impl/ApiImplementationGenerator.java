package com.nvp.orchestrator.service.impl;

import lombok.RequiredArgsConstructor;
import org.reflections.Reflections;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.http.ResponseEntity;
import org.springframework.javapoet.JavaFile;
import org.springframework.javapoet.MethodSpec;
import org.springframework.javapoet.TypeName;
import org.springframework.javapoet.TypeSpec;

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

@RequiredArgsConstructor
public class ApiImplementationGenerator {

    private final Path generatedProjectPath;
    private final Random random = new Random();
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

        System.out.println("Сгенерирован класс: " + implClassName);
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
        methodBuilder.addStatement("return $L", generateRandomValue(returnType));

        return methodBuilder.build();
    }

    // Генерация случайных данных по типу

    private String generateRandomValue(Type returnType) {
        if (returnType instanceof Class<?> returnClass) {
            // Обработка примитивов и известных типов
            if (returnClass == int.class || returnClass == Integer.class) {
                return random.nextInt(100) + "";
            } else if (returnClass == long.class || returnClass == Long.class) {
                return random.nextLong() + "L";
            } else if (returnClass == double.class || returnClass == Double.class) {
                return random.nextDouble() + "D";
            } else if (returnClass == boolean.class || returnClass == Boolean.class) {
                return random.nextBoolean() + "";
            } else if (returnClass == String.class) {
                return "\"" + "RandomString" + random.nextInt(100) + "\"";
            } else if (returnClass.isArray()) {
                // Генерация пустого массива
                return "new " + returnClass.getComponentType().getSimpleName() + "[0]";
            } else if (returnClass.isPrimitive()) {
                return "0"; // Значение по умолчанию для примитивов
            } else if (returnClass.isEnum()) {
                Object[] enumConstants = returnClass.getEnumConstants();
                return enumConstants != null && enumConstants.length > 0
                        ? returnClass.getSimpleName() + "." + enumConstants[0]
                        : "null";
            } else if (!returnClass.isInterface()) {
                // Используем случайный конструктор
                return generateRandomConstructorValue(returnClass);
            }
        } else if (returnType instanceof java.lang.reflect.ParameterizedType parameterizedType) {
            Type rawType = parameterizedType.getRawType();

            // Обработка ResponseEntity
            if (rawType == ResponseEntity.class) {
                Type responseType = parameterizedType.getActualTypeArguments()[0];
                if (responseType == Void.class) {
                    return "org.springframework.http.ResponseEntity.ok().build()";
                }
                return "org.springframework.http.ResponseEntity.ok(" + generateRandomValue(responseType) + ")";
            }

            // Обработка коллекций и карт
            if (rawType == List.class || rawType == ArrayList.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return "java.util.Collections.singletonList(" + generateRandomValue(elementType) + ")";
            } else if (rawType == Set.class || rawType == HashSet.class) {
                Type elementType = parameterizedType.getActualTypeArguments()[0];
                return "java.util.Collections.singleton(" + generateRandomValue(elementType) + ")";
            } else if (rawType == Map.class || rawType == HashMap.class) {
                Type keyType = parameterizedType.getActualTypeArguments()[0];
                Type valueType = parameterizedType.getActualTypeArguments()[1];
                return "java.util.Collections.singletonMap(" + generateRandomValue(keyType) + ", " + generateRandomValue(valueType) + ")";
            }
        }

        return "null"; // Если тип не поддерживается
    }

    // Генерация значения с использованием случайного конструктора
    private String generateRandomConstructorValue(Class<?> customClass) {
        Constructor<?>[] constructors = customClass.getDeclaredConstructors();

        // Фильтруем только доступные конструкторы
        List<Constructor<?>> accessibleConstructors = new ArrayList<>(Arrays.asList(constructors));

        if (accessibleConstructors.isEmpty()) {
            return "null"; // Если нет доступных конструкторов
        }

        // Выбираем случайный конструктор с максимальным количеством параметров
        Constructor<?> randomConstructor = accessibleConstructors.stream().max(Comparator.comparingInt(Constructor::getParameterCount)).get();
        // Генерируем параметры для конструктора
        StringBuilder constructorArgs = new StringBuilder();
        for (Parameter parameter : randomConstructor.getParameters()) {
            if (!constructorArgs.isEmpty()) {
                constructorArgs.append(", ");
            }
            constructorArgs.append(generateRandomValue(parameter.getParameterizedType()));
        }

        // Возвращаем строку для вызова конструктора
        return "new " + customClass.getSimpleName() + "(" + constructorArgs + ")";
    }

    // Проверка наличия конструктора по умолчанию
    private boolean hasDefaultConstructor(Class<?> clazz) {
        try {
            return clazz.getDeclaredConstructor() != null;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // Генерация mock-объекта для интерфейсов и классов без конструктора
    private String generateMockObject(Class<?> customClass) {
        String mockImplementation = customClass.getSimpleName() + "Mock";
        return "new " + mockImplementation + "()";
    }

}