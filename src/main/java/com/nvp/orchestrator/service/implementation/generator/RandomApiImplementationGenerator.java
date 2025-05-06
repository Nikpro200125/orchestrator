package com.nvp.orchestrator.service.implementation.generator;

import kotlin.Pair;
import lombok.extern.slf4j.Slf4j;
import org.springframework.javapoet.*;
import org.springframework.javapoet.MethodSpec.Builder;

import javax.lang.model.element.Modifier;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.nio.file.Path;

@Slf4j
public final class RandomApiImplementationGenerator extends ApiImplementationGenerator {

    public RandomApiImplementationGenerator(Path generatedProjectPath) {
        super(generatedProjectPath);
    }

    private MethodSpec generateMethodStub(Method method) {
        Pair<Builder, Type> methodBuilderAndReturnType = prepareSignature(method);
        Builder methodBuilder = methodBuilderAndReturnType.getFirst();
        Type returnType = methodBuilderAndReturnType.getSecond();

        // Добавляем заглушку для возвращаемого значения
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
            classBuilder.addMethod(generateMethodStub(method));
        }

        String packageName = apiInterface.getPackage().getName();
        saveGeneratedClass(classBuilder, packageName);

        log.info("Сгенерирован класс: {}", implClassName);
    }
}
