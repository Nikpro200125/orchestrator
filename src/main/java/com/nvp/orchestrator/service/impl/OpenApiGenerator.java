package com.nvp.orchestrator.service.impl;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.Path;

@Slf4j
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
                "--library", "spring-boot",
                "--additional-properties=" +
                        "interfaceOnly=true," +
                        "useSpringBoot3=true," +
                        "java8=true," +
                        "skipDefaultInterface=true," +
                        "additionalModelTypeAnnotations=@lombok.AllArgsConstructor@lombok.NoArgsConstructor"
        );
        pb.inheritIO(); // Отображаем процесс в консоли для отладки
        Process process = pb.start();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Failed to generate service. Exit code: " + exitCode);
        }
        log.info("Service generated successfully at: {}", tempDir.toAbsolutePath());
    }
}
