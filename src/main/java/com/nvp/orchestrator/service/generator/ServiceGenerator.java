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
public abstract sealed class ServiceGenerator permits ContractsServiceGenerator, RandomServiceGenerator {

    protected static final String RESOURCE_PLACEHOLDER_DIR = "src/main/resources/project_placeholder";
    protected static final String RESOURCE_ROOT_DIR = RESOURCE_PLACEHOLDER_DIR + "/root";
    protected static final String RESOURCES_DIR = RESOURCE_PLACEHOLDER_DIR + "/resources";
    protected static final String PROJECT_RESOURCES_DIR = "src/main/resources";
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

        copyFilesRelativeToRootFolder(tempDir, PROJECT_ROOT_DIR, Path.of(RESOURCE_ROOT_DIR));
        copyFilesRelativeToRootFolder(tempDir, PROJECT_RESOURCES_DIR, Path.of(RESOURCES_DIR));
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

    private void copyFilesRelativeToRootFolder(Path generatedBaseDir, String to, Path from) {
        try (Stream<Path> files = Files.walk(from)) {
            files.filter(Files::isRegularFile)
                    .forEach(file -> {
                        Path relativePath = from.relativize(file);
                        Path targetPath = generatedBaseDir.resolve(to).resolve(relativePath);
                        try {
                            Files.createDirectories(targetPath.getParent());
                            Files.copy(file, targetPath, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException e) {
                            log.error("Failed to copy file to project root", e);
                            throw new GenerationServiceException("Failed to copy file to project root");
                        }
                        log.debug("File copied to {}", targetPath);
                    });
        } catch (Exception e) {
            log.error("Failed to copy files to project root", e);
            throw new GenerationServiceException("Failed to copy files to project root");
        }
    }

    private void updatePomXML(Path tempDir) {
        try {
            Path existingPomXMLPath = Path.of(RESOURCE_PLACEHOLDER_DIR + "/pom.xml");
            Path pomXMLPath = tempDir.resolve("pom.xml");
            Files.copy(existingPomXMLPath, pomXMLPath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("pom.xml copied to {}", pomXMLPath);
        } catch (Exception e) {
            log.error("Failed to copy pom.xml", e);
            throw new GenerationServiceException("Failed to copy pom.xml");
        }
    }

    private void copyDockerfile(Path tempDir) {
        try {
            Path existingDockerfile = Path.of(RESOURCE_PLACEHOLDER_DIR + "/Dockerfile");
            Path dockerfilePath = tempDir.resolve("Dockerfile");
            Files.copy(existingDockerfile, dockerfilePath, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Dockerfile copied to {}", dockerfilePath);
        } catch (Exception e) {
            log.error("Failed to copy Dockerfile", e);
            throw new GenerationServiceException("Failed to copy Dockerfile");
        }
    }

}
