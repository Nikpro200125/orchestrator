package com.nvp.orchestrator.service.generator;

import com.nvp.orchestrator.exceptions.GenerationServiceException;
import com.nvp.orchestrator.service.util.OpenApiGenerator;
import com.nvp.orchestrator.service.util.DockerTools;
import com.nvp.orchestrator.service.util.MavenTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;


@Slf4j
public abstract sealed  class ServiceGenerator permits ContractsServiceGenerator, RandomServiceGenerator {

    protected static final String RESOURCE_ROOT_DIR = "src/main/resources/templates/root";
    protected static final String PROJECT_ROOT_DIR = "src/main/java/org/openapitools";
    protected static final String OPENAPI_SPEC_FILE_NAME = "openapi.yaml";

    public abstract String generateImplementation(MultipartFile file);

    protected void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new GenerationServiceException("File is empty");
        }
    }

    protected Path generateWorkingDirectory() {
        try {
            return Files.createTempDirectory("generated-service-" + System.currentTimeMillis());
        } catch (Exception e) {
            log.error("Failed to create working directory", e);
            throw new GenerationServiceException("Failed to create working directory");
        }
    }

    protected void generateServiceFromOpenApi(Path tempDir, Path openapiSpecPath) {
        try {
            OpenApiGenerator.generateSpringService(tempDir, openapiSpecPath);
        } catch (Exception e) {
            log.error("Failed to generate service", e);
            throw new GenerationServiceException("Failed to generate service");
        }

        copyFilesToProjectRoot(tempDir);
        updatePomXML(tempDir);
        MavenTools.compileGenerated(tempDir);
    }

    protected String buildAndDeployService(Path tempDir) {
        MavenTools.generateJar(tempDir);
        copyDockerfile(tempDir);
        String name = DockerTools.build(tempDir);
        DockerTools.start(name);
        return DockerTools.getUrl(name);
    }

    protected Path saveFileToWorkingDir(Path tempDir, String name, MultipartFile file) {
        try {
            Path filePath = tempDir.resolve(name);
            Files.copy(file.getInputStream(), filePath, StandardCopyOption.REPLACE_EXISTING);
            return filePath;
        } catch (Exception e) {
            log.error("Failed to save file to temp dir", e);
            throw new GenerationServiceException("Failed to save file to temp dir");
        }
    }

    private void copyFilesToProjectRoot(Path tempDir) {
        try (Stream<Path> files = Files.walk(Path.of(RESOURCE_ROOT_DIR))) {
            files.filter(Files::isRegularFile)
                    .forEach(file -> {
                        Path relativePath = Path.of(RESOURCE_ROOT_DIR).relativize(file);
                        Path targetPath = tempDir.resolve(PROJECT_ROOT_DIR).resolve(relativePath);
                        try {
                            Files.createDirectories(targetPath.getParent());
                            Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            log.error("Failed to copy file to project root", e);
                            throw new GenerationServiceException("Failed to copy file to project root");
                        }
                        log.info("File copied to {}", targetPath);
                    });
        } catch (Exception e) {
            log.error("Failed to copy files to project root", e);
            throw new GenerationServiceException("Failed to copy files to project root");
        }
    }

    private void updatePomXML(Path tempDir) {
        try {
            Path existingPomXMLPath = Path.of("src/main/resources/templates/pom.xml");
            Path pomXMLPath = tempDir.resolve("pom.xml");
            Files.copy(existingPomXMLPath, pomXMLPath, StandardCopyOption.REPLACE_EXISTING);
            log.info("pom.xml copied to {}", pomXMLPath);
        } catch (Exception e) {
            log.error("Failed to copy pom.xml", e);
            throw new GenerationServiceException("Failed to copy pom.xml");
        }
    }

    private void copyDockerfile(Path tempDir) {
        try {
            Path existingDockerfile = Path.of("src/main/resources/templates/Dockerfile");
            Path dockerfilePath = tempDir.resolve("Dockerfile");
            Files.copy(existingDockerfile, dockerfilePath, StandardCopyOption.REPLACE_EXISTING);
            log.info("Dockerfile copied to {}", dockerfilePath);
        } catch (Exception e) {
            log.error("Failed to copy Dockerfile", e);
            throw new GenerationServiceException("Failed to copy Dockerfile");
        }
    }

}
