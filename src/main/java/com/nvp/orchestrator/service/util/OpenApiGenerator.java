package com.nvp.orchestrator.service.util;

import com.nvp.orchestrator.exceptions.OpenApiGenerationException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
                        "generatedConstructorWithRequiredArgs=false," +
                        "generateConstructorWithAllArgs=true"
        );
        Process process = pb.start();
        StringBuilder processLog = new StringBuilder();


        // Считываем вывод процесса
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                processLog.append(line);
                log.debug(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            if (processLog.isEmpty()) {
               throw new OpenApiGenerationException("Failed to generate service. Possibly due to docker not being started.");
            }
            throw new OpenApiGenerationException("Failed to generate service. \n" + processLog);
        }
        log.info("Service generated successfully at: {}", tempDir.toAbsolutePath());
    }
}
