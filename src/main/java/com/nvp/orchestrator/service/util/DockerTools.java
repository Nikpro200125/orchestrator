package com.nvp.orchestrator.service.util;

import com.nvp.orchestrator.exceptions.DockerException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

@UtilityClass
@Slf4j
public final class DockerTools {

    public static String build(Path tempDir) {

        String name = "generated-api-service-" + System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder("docker", "build", "-t", name, ".");
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
                log.info("Образ успешно собран!");

            } else {
                log.error("Failed to build project");
            }

        } catch (IOException | InterruptedException e) {
            log.error("Failed to build project", e);
            throw new DockerException("Failed to build project.\n" + processLog);
        }
        return name;
    }

    public static void start(String name) {
        ProcessBuilder pb = new ProcessBuilder("docker", "run", "-p", ":8080", "-d", name);
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
                log.info("Проект успешно запущен!");
            } else {
                log.error("Failed to start project");
            }

        } catch (IOException | InterruptedException e) {
            log.error("Failed to start project", e);
            throw new DockerException("Failed to start project.\n" + processLog);
        }
    }

    //get url to container by imageName
    public static String getUrl(String ImageName) {
        ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter", "\"ancestor=" + ImageName + "\"", "--format", "\"{{.Names}} - {{.Ports}}\"");
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства
        StringBuilder processLog = new StringBuilder();

        StringBuilder result = new StringBuilder();
        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    processLog.append(line);
                    result.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                log.error("Failed to get url");
            }

        } catch (IOException | InterruptedException e) {
            log.error("Failed to get url", e);
            throw new DockerException("Failed to get url.\n" + processLog);
        }
        return result.toString().trim();
    }
}
