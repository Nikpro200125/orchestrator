package com.nvp.orchestrator.service;

import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.nio.file.Path;

@UtilityClass
public class OpenApiGenerator {

    public static void generateSpringService(Path tempDir, Path openapiSpec) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "docker", "run", "--rm",
                "-v", tempDir.toAbsolutePath() + ":/local",
                "openapitools/openapi-generator-cli",
                "generate",
                "-i", "/local/" + openapiSpec.getFileName(),
                "-g", "spring",
                "-o", "/local",
                "--additional-properties=javaVersion=21," +
                        "springBootVersion=3.4.0," +
                        "interfaceOnly=true," +
                        "groupId=org.openapitools," +
                        "artifactId=generated-service-" + System.currentTimeMillis() + "," +
                        "artifactVersion=1.0.0," +
                        "apiPackage=org.openapitools.api," +
                        "modelPackage=org.openapitools.model," +
                        "useSpringBoot3=true"
        );
        pb.inheritIO(); // Отображаем процесс в консоли для отладки
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to generate service. Exit code: " + exitCode);
        }
        System.out.println("Service generated successfully at: " + tempDir.toAbsolutePath());
    }
}
