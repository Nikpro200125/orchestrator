package com.nvp.orchestrator.service.util;

import com.nvp.orchestrator.exceptions.ProjectCompilationException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

@UtilityClass
@Slf4j
public final class MavenTools {

    public static void compileGenerated(Path tempDir) {

        // Запускаем "mvn compile"
        ProcessBuilder pb = new ProcessBuilder(Path.of("src/main/resources/maven/bin/mvn.cmd").toAbsolutePath().toString(), "compile");
        pb.directory(tempDir.toFile()); // устанавливаем рабочую директорию
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства
        StringBuilder processLog = new StringBuilder();

        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processLog.append(line);
                    log.debug(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Проект успешно скомпилирован!");
            } else {
                log.error("Failed to compile project");
                throw new ProjectCompilationException("Failed to compile project with interfaces.\n" + processLog);
            }

        } catch (IOException | InterruptedException e) {
            log.error("Failed to compile project", e);
        }
    }

    public static void generateJar(Path tempDir) {

        // Запускаем "mvn package"
        ProcessBuilder pb = new ProcessBuilder(Path.of("src/main/resources/maven/bin/mvn.cmd").toAbsolutePath().toString(), "package");
        pb.directory(tempDir.toFile()); // устанавливаем рабочую директорию
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства
        StringBuilder processLog = new StringBuilder();

        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processLog.append(line);
                    log.debug(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                log.info("Проект успешно собран!");
            } else {
                log.error("Failed to generate JAR with implementation");
                throw new RuntimeException("Failed to generate JAR with implementation.\n" + processLog);
            }

        } catch (IOException | InterruptedException e) {
            log.error("Failed to generate JAR", e);
        }
    }
}
