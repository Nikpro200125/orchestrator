package com.nvp.orchestrator.service;

import lombok.experimental.UtilityClass;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

@UtilityClass
public class MavenTools {

    public static void compileGenerated(Path tempDir) {

        // Запускаем "mvn compile"
        ProcessBuilder pb = new ProcessBuilder(Path.of("src/main/resources/maven/bin/mvn.cmd").toAbsolutePath().toString(), "compile");
        pb.directory(tempDir.toFile()); // устанавливаем рабочую директорию
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства

        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
                    System.out.println(line);
                }
            }

            int exitCode = process.waitFor();
            if (exitCode == 0) {
                System.out.println("Проект успешно скомпилирован!");
            } else {
                System.err.println("Произошла ошибка при компиляции проекта. Код выхода: " + exitCode);
            }

        } catch (IOException | InterruptedException e) {
            System.err.println("Ошибка при выполнении команды mvn compile: " + e.getMessage());
        }
    }

    public static void generateJar(Path tempDir) {

        // Запускаем "mvn package"
        ProcessBuilder pb = new ProcessBuilder(Path.of("src/main/resources/maven/bin/mvn.cmd").toAbsolutePath().toString(), "package");
        pb.directory(tempDir.toFile()); // устанавливаем рабочую директорию
        pb.redirectErrorStream(true); // перенаправляем stderr в stdout для удобства

        try {
            Process process = pb.start();

            // Считываем вывод процесса
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while((line = reader.readLine()) != null) {
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
    }
}
