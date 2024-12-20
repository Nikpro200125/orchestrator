package com.nvp.orchestrator.service.impl;

import lombok.experimental.UtilityClass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

@UtilityClass
public class DockerTools {

    public static String build(Path tempDir) {

        String name = "generated-api-service-" + System.currentTimeMillis();
        ProcessBuilder pb = new ProcessBuilder("docker", "build",  "-t", name, ".");
        pb.directory(tempDir.toFile()); // устанавливаем рабочую директорию
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства

        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Проект успешно собран!");
            } else {
                System.err.println("Произошла ошибка при сборке проекта. Код выхода: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка при выполнении команды mvn package: " + e.getMessage());
        }
        return name;
    }

    public static void start(String name) {
        ProcessBuilder pb = new ProcessBuilder("docker", "run", "-p", ":8080", "-d", name);
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства

        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Проект успешно запущен!");
            } else {
                System.err.println("Произошла ошибка при запуске проекта. Код выхода: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка при выполнении команды docker run: " + e.getMessage());
        }
    }

    //get url to container by imageName
    public static String getUrl(String ImageName) {
        ProcessBuilder pb = new ProcessBuilder("docker", "ps", "--filter", "\"ancestor=" + ImageName + "\"", "--format", "\"{{.Names}} - {{.Ports}}\"");
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства

        StringBuilder result = new StringBuilder();
        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    result.append(line).append("\n");
                }
            }

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                System.err.println("Произошла ошибка при запуске проекта. Код выхода: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка при выполнении команды docker run: " + e.getMessage());
        }
        return result.toString().trim();
    }
}
