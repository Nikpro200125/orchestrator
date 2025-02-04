package com.nvp.orchestrator.service.implementation.generator;

import com.nvp.orchestrator.exceptions.GenerationServiceException;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.reflections.Reflections;
import org.reflections.scanners.Scanner;
import org.reflections.scanners.Scanners;
import org.reflections.util.ConfigurationBuilder;
import org.springframework.javapoet.JavaFile;
import org.springframework.javapoet.MethodSpec;
import org.springframework.javapoet.TypeSpec;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;

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
}
