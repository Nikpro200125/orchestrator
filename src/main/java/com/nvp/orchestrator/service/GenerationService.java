package com.nvp.orchestrator.service;

import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@RequiredArgsConstructor
public class GenerationService {

    private Path generateWorkingDirectory() {
        try {
            return Files.createTempDirectory("generated-service-" + System.currentTimeMillis());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create working directory", e);
        }
    }

    private void copyApplicationJava(Path tempDir) throws IOException {
        Path existingApplicationJavaPath = Path.of("src/main/resources/templates/Application.java");
        Path applicationJavaPath = tempDir.resolve("src/main/java/org/openapitools/Application.java");
        Files.createDirectories(applicationJavaPath.getParent());
        Files.copy(existingApplicationJavaPath, applicationJavaPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void updatePomXML(Path tempDir) throws IOException {
        Path existingPomXMLPath = Path.of("src/main/resources/templates/pom.xml");
        Path pomXMLPath = tempDir.resolve("pom.xml");
        Files.copy(existingPomXMLPath, pomXMLPath, StandardCopyOption.REPLACE_EXISTING);
    }

    private void copyDockerfile(Path tempDir) throws IOException {
        Path existingDockerfile = Path.of("src/main/resources/templates/Dockerfile");
        Path dockerfilePath = tempDir.resolve("Dockerfile");
        Files.copy(existingDockerfile, dockerfilePath, StandardCopyOption.REPLACE_EXISTING);
    }

    public String generate(MultipartFile openapiFile) throws Exception {
        if (openapiFile.isEmpty()) {
            throw new IllegalArgumentException("OpenAPI spec file is empty");
        }

        Path tempDir = generateWorkingDirectory();
        Path openapiSpecPath = tempDir.resolve("openapi.yaml");
        Files.copy(openapiFile.getInputStream(), openapiSpecPath, StandardCopyOption.REPLACE_EXISTING);

        try {
            OpenApiGenerator.generateSpringService(tempDir, openapiSpecPath);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate service", e);
        }

        copyApplicationJava(tempDir);
        updatePomXML(tempDir);
        MavenTools.compileGenerated(tempDir);

        ApiImplementationGenerator apiImplementationGenerator =
                new ApiImplementationGenerator(tempDir);
        try {
            apiImplementationGenerator.generate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate API implementations", e);
        }

        MavenTools.generateJar(tempDir);
        Thread.sleep(1000); // Ждём, пока Maven закончит работу

        copyDockerfile(tempDir);

        String name = DockerTools.build(tempDir);
        DockerTools.start(name);

        return tempDir.toAbsolutePath().toString();
    }
}
